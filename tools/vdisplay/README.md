# tools/vdisplay — M1-1 shell 助手（建屏/承载/截帧 机制证明）

验证结论（Android 16 真机）：**免 root 的 shell-UID 助手可以创建可信虚拟屏、把真实 App 承载上去、并截取其帧。**

## 关键实现要点（踩坑记录）

1. **必须用公开 API `DisplayManager(context).createVirtualDisplay(...)`**，不能反射 `DisplayManagerGlobal`：
   - Android 15/16 会校验 `packageName` 与调用 uid 一致。
   - 正解：`DisplayManager::class.java.getDeclaredConstructor(Context::class.java).newInstance(shellCtx)`，
     `shellCtx = systemContext.createPackageContext("com.android.shell", 0)`（uid 2000 与 shell 进程匹配）。
2. **全套隐藏 flags**（`flags=0x17dc9` 实测）：
   `PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|ROTATES_WITH_CONTENT|DESTROY_CONTENT_ON_REMOVAL|TRUSTED|OWN_DISPLAY_GROUP|ALWAYS_UNLOCKED|TOUCH_FEEDBACK_DISABLED|OWN_FOCUS|STEAL_TOP_FOCUS_DISABLED`。
   - `trusted=true` 成功（本机 shell 有 `ADD_TRUSTED_DISPLAY`）；无权限时退回去掉 TRUSTED 一组。
3. **裁帧用 ImageReader**（surface = `ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2).surface`），读帧存 PNG；
   `screencap -d` 对虚拟屏不可靠（报 not valid）。
4. 启动目标 App：`am start --display <id> -n <pkg>/<act>`（组件须带 `/`）。

## 构建/运行

```bash
./build.sh                      # 产出 out/classes.dex
adb push out/classes.dex /data/local/tmp/helper.dex
adb push test.sh /data/local/tmp/test.sh
adb shell sh /data/local/tmp/test.sh
```

## 产物

- `HelperMain.java`：app_process 助手（建屏 + 承载 + ImageReader 截帧存 PNG）。
- `test.sh`：一键验证（建屏→host Settings→截帧→打印日志）。
- 证据：`logs/20261107-2/mfa_frame_settings.png`（Settings 虚屏帧）。

> 说明：本目录为 M1 验证用的独立编译产物；正式实现将把该逻辑并入 `core:shell` 模块，连同免 root ADB 客户端自举一起（M1-2/3/4）。
