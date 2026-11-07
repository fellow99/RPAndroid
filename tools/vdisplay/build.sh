#!/usr/bin/env bash
# M1-1 shell-helper build script (standalone app_process, shell uid).
# Usage: ./build.sh && <push helper.dex to /data/local/tmp>
set -euo pipefail
SDK="${ANDROID_SDK:-C:/Users/fello/AppData/Local/Android/Sdk}"
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac -classpath "$SDK/platforms/android-36/android.jar" -d out HelperMain.java
"$SDK/build-tools/36.0.0/d8.bat" --min-api 26 --output out out/helper/HelperMain.class
echo "OK: out/classes.dex"
