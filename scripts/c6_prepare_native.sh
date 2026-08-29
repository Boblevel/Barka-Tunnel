#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="${RUNNER_TEMP:-/tmp}/barka-c6-native"
JNI="$ROOT/app/src/main/jniLibs"
rm -rf "$TMP" "$JNI"
mkdir -p "$TMP" "$JNI/arm64-v8a" "$JNI/armeabi-v7a"

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
ARMV7_TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
ARMV7_CC="$ARMV7_TOOLCHAIN/armv7a-linux-androideabi24-clang"
ARMV7_CXX="$ARMV7_TOOLCHAIN/armv7a-linux-androideabi24-clang++"
test -x "$ARMV7_CC" || { echo "Compilateur Android ARMv7 C introuvable"; exit 1; }
test -x "$ARMV7_CXX" || { echo "Compilateur Android ARMv7 C++ introuvable"; exit 1; }

# Xray/VLESS - version épinglée.
# ARM64 conserve l’asset Android officiel. ARMv7 est compilé depuis la même
# source officielle pour Android afin de ne jamais embarquer un binaire Linux
# générique dans un APK Android.
XRAY_TAG="v26.3.27"
asset="Xray-android-arm64-v8a.zip"
url="https://github.com/XTLS/Xray-core/releases/download/${XRAY_TAG}/${asset}"
curl -fL --retry 3 "$url" -o "$TMP/$asset"
mkdir -p "$TMP/xray-arm64-v8a"
unzip -q "$TMP/$asset" -d "$TMP/xray-arm64-v8a"
xray_bin="$(find "$TMP/xray-arm64-v8a" -type f -name xray | head -1)"
test -n "$xray_bin" || { echo "Xray absent pour arm64-v8a"; exit 1; }
cp "$xray_bin" "$JNI/arm64-v8a/libbarka_xray.so"
chmod 0755 "$JNI/arm64-v8a/libbarka_xray.so"

git clone --depth 1 --branch "$XRAY_TAG" https://github.com/XTLS/Xray-core.git "$TMP/xray-src"
(
  cd "$TMP/xray-src"
  GOOS=android GOARCH=arm GOARM=7 CGO_ENABLED=1 CC="$ARMV7_CC" \
    go build -trimpath -buildvcs=false \
      -ldflags="-s -w -buildid= -checklinkname=0" \
      -o "$JNI/armeabi-v7a/libbarka_xray.so" ./main
)
chmod 0755 "$JNI/armeabi-v7a/libbarka_xray.so"

# DNSTT client - compilé depuis la source officielle pour les deux ABI Android ARM.
git clone https://www.bamsoftware.com/git/dnstt.git "$TMP/dnstt"
(
  cd "$TMP/dnstt"
  GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
    go build -trimpath -o "$JNI/arm64-v8a/libbarka_dnstt.so" ./dnstt-client
  # android/arm impose l’édition de liens externe via cgo. Utiliser
  # explicitement le toolchain NDK évite l’échec Go « cgo is not enabled ».
  GOOS=android GOARCH=arm GOARM=7 CGO_ENABLED=1 \
    CC="$ARMV7_CC" CXX="$ARMV7_CXX" \
    go build -trimpath -o "$JNI/armeabi-v7a/libbarka_dnstt.so" ./dnstt-client
)
chmod 0755 \
  "$JNI/arm64-v8a/libbarka_dnstt.so" \
  "$JNI/armeabi-v7a/libbarka_dnstt.so"

# BadVPN tun2socks + UDPGW JNI. Le projet amont utilise un ancien couple
# Gradle/AGP qui échoue à la configuration sur le runner GitHub actuel.
# On compile donc uniquement le module JNI CMake, sans passer par son Gradle.
git clone --depth 1 https://github.com/LondonX/tun2socks-android.git "$TMP/tun2socks"

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

# Vérifier que le wrapper Java embarqué reste compatible avec la JNI compilée
# depuis LondonX : le symbole natif exposé est start_tun2socks(String[]), tandis
# que startTun2Socks(...) doit rester le wrapper Java qui construit les arguments.
TUN_JAVA="$ROOT/app/src/main/java/com/LondonX/tun2socks/Tun2Socks.java"
test -f "$TUN_JAVA"
grep -Fq 'private static native int start_tun2socks(String[] args);' "$TUN_JAVA" || {
  echo "Wrapper Java tun2socks incompatible avec la JNI LondonX"
  exit 1
}
if grep -Fq 'native boolean startTun2Socks' "$TUN_JAVA"; then
  echo "Ancienne signature JNI startTun2Socks incompatible détectée"
  exit 1
fi

# Le build doit être complet pour chaque ABI Android supportée.
for abi in arm64-v8a armeabi-v7a; do
  for core in libbarka_xray.so libbarka_dnstt.so libtun2socks.so; do
    test -s "$JNI/$abi/$core" || {
      echo "Moteur C6 absent : $abi/$core"
      exit 1
    }
  done
done

readelf -h "$JNI/arm64-v8a/libbarka_xray.so" | grep -q 'AArch64' || { echo "Xray ARM64 invalide"; exit 1; }
readelf -h "$JNI/armeabi-v7a/libbarka_xray.so" | grep -q 'ARM' || { echo "Xray ARMv7 invalide"; exit 1; }
readelf -h "$JNI/arm64-v8a/libbarka_dnstt.so" | grep -q 'AArch64' || { echo "DNSTT ARM64 invalide"; exit 1; }
readelf -h "$JNI/armeabi-v7a/libbarka_dnstt.so" | grep -q 'ARM' || { echo "DNSTT ARMv7 invalide"; exit 1; }

echo "===== C6 native cores ====="
find "$JNI" -maxdepth 2 -type f -print -exec file {} \;
