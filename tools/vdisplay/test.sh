#!/system/bin/sh
# M1-1 verify v2: create VD (Mika method), host Settings, capture frame via ImageReader.
CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin helper.HelperMain 720 1560 400 com.android.settings .Settings >/data/local/tmp/vd.log 2>&1 &
HELPER_PID=$!
sleep 16
echo "===== vd.log ====="
cat /data/local/tmp/vd.log
echo "===== frame file ====="
ls -l /data/local/tmp/mfa_frame.png 2>&1
echo "===== hosted task? (displayId) ====="
dumpsys activity activities 2>/dev/null | grep -iE "display #|mDisplayId|ResumedActivity|com.android.settings" | head -10
kill $HELPER_PID 2>/dev/null || true
echo "===== done ====="
