# core:shell / adb —— 免 root ADB 客户端（移植自 Mika）

移植自 [Mika](https://github.com/wangzan101/Mika) `core/adb/`（Apache-2.0，保留署名）。
纯 JVM ADB 协议层（配对 SPAKE2/Ed25519、TLS、CNXN/AUTH、流多路复用），仅供 `core:shell`
作为"免 root 自举拉起 shell 助手"的传输底座。Android 侧（钥匙存储、服务发现、NoRootShell）
在 core/shell 其余部分实现。
