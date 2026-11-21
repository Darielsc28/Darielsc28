#!/usr/bin/env bash
# Compile PubStop en APK signé sans Android Studio ni Gradle.
# Prérequis : JDK 17+, Python 3 avec pip, curl. Linux x86-64.
set -euo pipefail
cd "$(dirname "$0")"

TC=.toolchain
OUT=build
PKG_DIR=src/io/github/darielsc28/pubstop
KEYSTORE=pubstop.keystore
KEYPASS=pubstop
MAVEN=https://repo.maven.apache.org/maven2

JDK_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}

fetch() { [ -s "$2" ] || { echo "Téléchargement de $(basename "$2")…"; curl -fsSL -o "$2.part" "$1" && mv "$2.part" "$2"; }; }

mkdir -p "$TC"
fetch "$MAVEN/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar" "$TC/android.jar"
fetch "https://storage.googleapis.com/r8-releases/raw/8.5.35/r8lib.jar" "$TC/r8.jar"
fetch "$MAVEN/com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar" "$TC/apksig.jar"
if [ ! -x "$TC/aapt2" ]; then
  echo "Téléchargement de aapt2…"
  rm -rf "$TC/whl" && mkdir -p "$TC/whl"
  python3 -m pip download aapt2==0.2.1 --no-deps -q -d "$TC/whl"
  unzip -oq -j "$TC"/whl/aapt2-*.whl 'aapt2/bin/Linux/aapt2' -d "$TC"
  chmod +x "$TC/aapt2"
fi

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/test" "$OUT/dex" dist

echo "== Tests =="
javac -nowarn -d "$OUT/test" "$PKG_DIR/DnsPacket.java" "$PKG_DIR/Blocklist.java" test/DnsTest.java
java -cp "$OUT/test" io.github.darielsc28.pubstop.DnsTest

echo "== Ressources =="
"$TC/aapt2" compile --dir res -o "$OUT/res.zip"
"$TC/aapt2" link -o "$OUT/base.apk" -I "$TC/android.jar" --manifest AndroidManifest.xml \
  -A assets --java "$OUT/gen" "$OUT/res.zip"

echo "== Compilation =="
# android-all ne contient pas java.* : ces classes viennent du JDK (API Java 8).
javac -nowarn -Xlint:-options --release 8 -cp "$TC/android.jar" \
  -d "$OUT/classes" "$PKG_DIR"/*.java $(find "$OUT/gen" -name '*.java')
java -cp "$TC/r8.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "$TC/android.jar" --lib "$JDK_HOME" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== Assemblage et signature =="
(cd "$OUT/dex" && zip -q ../base.apk classes.dex)
python3 tools/align.py "$OUT/base.apk" "$OUT/aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass "$KEYPASS" \
    -alias pubstop -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=PubStop" 2>/dev/null
fi
javac -nowarn -cp "$TC/apksig.jar" -d "$OUT/sign" tools/Sign.java
java --add-exports java.base/sun.security.x509=ALL-UNNAMED --add-exports java.base/sun.security.pkcs=ALL-UNNAMED \
  -cp "$TC/apksig.jar:$OUT/sign" Sign "$KEYSTORE" "$KEYPASS" pubstop "$OUT/aligned.apk" dist/PubStop.apk
python3 tools/align.py --check dist/PubStop.apk

ls -l dist/PubStop.apk
