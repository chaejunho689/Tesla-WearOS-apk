#!/usr/bin/env bash
# 갤럭시워치(Wear OS) 네이티브 앱 수동 빌드
# 리소스(R.java) + assets + VectorDrawable 포함. build-tools 35의 d8 사용.
set -e
SDK="/c/Users/smile/android-sdk"
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-34/android.jar"
cd "$(dirname "$0")"

rm -rf build gen
mkdir -p build/classes gen

echo "=== aapt package (리소스+에셋, R.java 생성) ==="
"$BT/aapt.exe" package -f -m \
  -M AndroidManifest.xml \
  -S res -A assets \
  -J gen \
  -I "$(cygpath -w "$AJ")" \
  -F build/app.unaligned.apk

echo "=== javac (R.java + MainActivity) ==="
javac -source 8 -target 8 -bootclasspath "$(cygpath -w "$AJ")" -d build/classes \
  gen/com/hongcha/teslawatch/R.java \
  src/com/hongcha/teslawatch/MainActivity.java 2>&1 | grep -viE "warning|obsolete|deprecat|^Note" || true

echo "=== d8 ==="
cmd //c "$(cygpath -w "$BT/d8.bat")" --min-api 30 --output build \
  --lib "$(cygpath -w "$AJ")" \
  build/classes/com/hongcha/teslawatch/*.class

echo "=== dex 추가 + 정렬 + 서명 ==="
( cd build && "$BT/aapt.exe" add app.unaligned.apk classes.dex >/dev/null )
"$BT/zipalign.exe" -f 4 build/app.unaligned.apk build/app.aligned.apk
cmd //c "$(cygpath -w "$BT/apksigner.bat")" sign \
  --ks tesla-watch.keystore --ks-pass pass:watchpass --ks-key-alias teslawatch \
  --out build/tesla-watch-signed.apk build/app.aligned.apk

cp build/tesla-watch-signed.apk "/c/Users/smile/tesla-bridge/bridge/data/tesla-watch.apk"
cmd //c "$(cygpath -w "$BT/apksigner.bat")" verify "/c/Users/smile/tesla-bridge/bridge/data/tesla-watch.apk" && echo "빌드·서명 OK → bridge/data/tesla-watch.apk"
