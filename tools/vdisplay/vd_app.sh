#!/system/bin/sh
# App-triggered helper launcher (executed via app's own adbd connection).
# setsid detaches the helper from the adbd exec session so it survives the exec exit.
rm -f /data/local/tmp/vd_app.log
setsid sh -c 'CLASSPATH=/data/local/tmp/helper.dex exec app_process /system/bin helper.HelperMain 720 1560 400 com.android.settings .Settings >/data/local/tmp/vd_app.log 2>&1' </dev/null >/dev/null 2>&1 &
echo "launched"
