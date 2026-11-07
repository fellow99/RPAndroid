#!/system/bin/sh
# M1-3 inject test (clean): host Settings, ONLY swipe (scroll) via input -d, compare 2 real frames.
CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin helper.HelperMain 720 1560 400 com.android.settings .Settings >/data/local/tmp/vd.log 2>&1 &
HELPER_PID=$!
sleep 9
ID=$(sed -n 's/.*MFA_VD_ID=//p' /data/local/tmp/vd.log)
echo "===== ID=[$ID] ====="
sleep 3
echo "===== input -d $ID swipe up (scroll down) x2 ====="
input -d "$ID" swipe 360 1300 360 500 300 2>&1
sleep 1
input -d "$ID" swipe 360 1300 360 400 300 2>&1
sleep 8
echo "===== helper log ====="
cat /data/local/tmp/vd.log | grep -E "FRAME|CREATED"
echo "===== frames ====="
ls -l /data/local/tmp/mfa_before.png /data/local/tmp/mfa_after.png 2>&1
echo "md5 before:"; md5sum /data/local/tmp/mfa_before.png 2>&1
echo "md5 after:";  md5sum /data/local/tmp/mfa_after.png 2>&1
kill $HELPER_PID 2>/dev/null || true
echo "===== done ====="
