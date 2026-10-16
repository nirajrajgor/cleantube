#!/usr/bin/env bash
# Builds CleanTube.apk without Android Studio.
# Needs: java/javac, and in $TOOLS: aapt2, android.jar, d8.jar, apksigner.jar
set -euo pipefail
cd "$(dirname "$0")"
TOOLS="${TOOLS:?set TOOLS to the folder with aapt2, android.jar, d8.jar, apksigner.jar}"
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT"/{res,classes,dex}

echo "1/6 icons";   python3 make_icons.py
echo "2/6 resources"
"$TOOLS/aapt2" compile --dir app/res -o "$OUT/res/res.zip"
"$TOOLS/aapt2" link -o "$OUT/base.apk" -I "$TOOLS/android.jar" \
  --manifest app/AndroidManifest.xml -A app/assets "$OUT/res/res.zip" \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 2 --version-name 1.1

echo "3/6 compile"
javac --release 8 -Xlint:-options -classpath "$TOOLS/android.jar" -d "$OUT/classes" \
  $(find app/src -name '*.java')

echo "4/6 dex"
java -cp "$TOOLS/d8.jar" com.android.tools.r8.D8 --release --min-api 24 \
  --lib "$TOOLS/android.jar" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "5/6 package"
python3 - "$OUT" <<'PY'
import sys, zipfile
out = sys.argv[1]
with zipfile.ZipFile(f"{out}/base.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write(f"{out}/dex/classes.dex", "classes.dex")
PY
python3 zipalign.py "$OUT/base.apk" "$OUT/aligned.apk"

echo "6/6 sign"
if [ ! -f cleantube.keystore ]; then
  keytool -genkeypair -keystore cleantube.keystore -storepass cleantube -keypass cleantube \
    -alias cleantube -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=CleanTube, O=Personal" 2>/dev/null
fi
java -jar "$TOOLS/apksigner.jar" sign --ks cleantube.keystore --ks-pass pass:cleantube \
  --ks-key-alias cleantube --key-pass pass:cleantube \
  --out CleanTube.apk "$OUT/aligned.apk"
java -jar "$TOOLS/apksigner.jar" verify --verbose CleanTube.apk | head -6
echo "Done: $(pwd)/CleanTube.apk"
