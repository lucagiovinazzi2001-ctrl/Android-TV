#!/usr/bin/env bash
# Compila Essenziale.apk senza Android Studio/Gradle.
# Requisiti (Ubuntu/Debian): sudo apt install aapt android-sdk-platform-23 dalvik-exchange apksigner zipalign openjdk-17-jdk
set -euo pipefail
cd "$(dirname "$0")"
SDK_JAR="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
DX="${DX:-$(command -v dalvik-exchange || command -v dx)}"
KS="keystore/essenziale.jks"
OUT="../toolkit/Essenziale.apk"

rm -rf build && mkdir -p build/gen build/obj
[ -f "$KS" ] || { mkdir -p keystore; keytool -genkeypair -keystore "$KS" -storepass essenziale -keypass essenziale \
    -alias essenziale -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=Essenziale TV Launcher" >/dev/null; }

aapt package -f -m -M AndroidManifest.xml -S res -I "$SDK_JAR" -J build/gen -F build/unsigned.apk
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$SDK_JAR" \
    -d build/obj $(find src build/gen -name '*.java')
"$DX" --dex --min-sdk-version=21 --output=build/classes.dex build/obj
( cd build && aapt add -f unsigned.apk classes.dex >/dev/null )
zipalign -f -p 4 build/unsigned.apk build/aligned.apk
apksigner sign --v4-signing-enabled false --ks "$KS" --ks-pass pass:essenziale --key-pass pass:essenziale --out "$OUT" build/aligned.apk
apksigner verify "$OUT"
echo "OK -> $(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
