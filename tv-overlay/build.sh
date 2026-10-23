#!/bin/zsh
# Builds and signs tv-overlay.apk without Gradle, using the Android SDK command-line tools.
# Needs: JDK 17 (brew install openjdk@17), SDK platform android-31 and build-tools 34.0.0.
set -euo pipefail
cd "${0:h}"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-31/android.jar"
KEYSTORE="${KEYSTORE:-$HOME/.android/tv-overlay.keystore}"

rm -rf build && mkdir -p build/classes build/dex

"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 30 --target-sdk-version 31 --version-code 1 --version-name 1.0

"$JAVA_HOME/bin/javac" -source 1.8 -target 1.8 -bootclasspath "$JAR" -nowarn \
    -d build/classes src/com/gbicanic/tvoverlay/*.java

"$BT/d8" --min-api 30 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)

"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

if [[ ! -f "$KEYSTORE" ]]; then
    mkdir -p "${KEYSTORE:h}"
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias tvoverlay \
        -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android \
        -dname "CN=TV Weather Overlay"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out tv-overlay.apk build/aligned.apk

echo "built ${0:h}/tv-overlay.apk"
