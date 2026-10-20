#!/usr/bin/env bash
# Builds CleanTube.apk without Android Studio.
# Needs java/javac 17+ and python3. Settings come from .env, see .env.example.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=1
VERSION_NAME=1.0

if [ -f .env ]; then set -a; . ./.env; set +a; fi
TOOLS="${TOOLS:?set TOOLS in .env to the folder with aapt2, android.jar, d8.jar, apksigner.jar}"
KEYSTORE="${KEYSTORE:-release.keystore}"
KEY_ALIAS="${KEY_ALIAS:-cleantube}"
export KEYSTORE_PASS="${KEYSTORE_PASS:?set KEYSTORE_PASS in .env}"

OUT=build
rm -rf "$OUT" && mkdir -p "$OUT"/{res,classes,dex}

echo "1/5 resources"
"$TOOLS/aapt2" compile --dir app/res -o "$OUT/res/res.zip"
"$TOOLS/aapt2" link -o "$OUT/base.apk" -I "$TOOLS/android.jar" \
  --manifest app/AndroidManifest.xml -A app/assets "$OUT/res/res.zip" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo "2/5 compile"
javac --release 8 -Xlint:-options -classpath "$TOOLS/android.jar" -d "$OUT/classes" \
  $(find app/src -name '*.java')

echo "3/5 dex"
java -cp "$TOOLS/d8.jar" com.android.tools.r8.D8 --release --min-api 24 \
  --lib "$TOOLS/android.jar" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "4/5 package"
python3 - "$OUT" <<'PY'
import sys, zipfile
out = sys.argv[1]
with zipfile.ZipFile(f"{out}/base.apk", "a", zipfile.ZIP_DEFLATED) as z:
    z.write(f"{out}/dex/classes.dex", "classes.dex")
PY
python3 zipalign.py "$OUT/base.apk" "$OUT/aligned.apk"

echo "5/5 sign"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
    -storepass:env KEYSTORE_PASS -alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 \
    -validity 10000 -dname "CN=CleanTube" 2>/dev/null
  echo "Created a new signing key: $KEYSTORE. Back it up with .env."
  echo "Every future update must be signed with this key."
fi
java -jar "$TOOLS/apksigner.jar" sign --ks "$KEYSTORE" --ks-pass env:KEYSTORE_PASS \
  --ks-key-alias "$KEY_ALIAS" --v4-signing-enabled false \
  --out CleanTube.apk "$OUT/aligned.apk"
java -jar "$TOOLS/apksigner.jar" verify --verbose CleanTube.apk | head -6
echo "Done: $(pwd)/CleanTube.apk ($VERSION_NAME, code $VERSION_CODE)"
