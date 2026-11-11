# Release 签名与 GitHub Actions 发布

本文档覆盖 release 版 APK 的本地签名、GitHub Actions 自动构建与 tag 发布流程。

## 一、本地签名材料

签名材料**不入库**：`keystore.properties`、`*.jks`、`*.keystore` 均由 `.gitignore` 排除。

### 1. 生成密钥库

在工程根目录执行（把口令换成你自己的强口令）：

```bash
# Windows CMD / PowerShell 用 && 或分号连接
keytool -genkeypair -v -keystore rpandroid-release.jks -alias rpandroid \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=RPAndroid, OU=fellow99, O=fellow99, L=Beijing, ST=Beijing, C=CN"
```

> `-validity 10950` = 30 年。**妥善保管** `rpandroid-release.jks` 与两组口令：Android 上架后**
> 更换签名即无法覆盖安装/更新**（Google Play 的 key 升级是独立机制）。建议离线双备份。

### 2. 生成本地签名配置

复制模板并填真实值：

```bash
cp keystore.properties.sample keystore.properties
# 编辑 keystore.properties 填入 storePassword / keyAlias / keyPassword
```

有了 `keystore.properties`，本地 `./gradlew :app:assembleRelease` 即产出由 release key 签名的 APK。

### 3. 校验

```bash
keytool -list -keystore rpandroid-release.jks -storepass <storePassword>
```

## 二、GitHub Actions 自动构建

workflow：`.github/workflows/build-release.yml`

- 触发：push `v*` tag（构建 + 发布 Release）；或 `workflow_dispatch` 手动（只构建留 artifact，不发布）。
- 签名 secrets（仓库 Settings → Secrets and variables → Actions）：
  - `ANDROID_KEYSTORE` — keystore 文件经 `base64 -w0` 编码后的字符串
  - `KEYSTORE_PASSWORD` — store 口令
  - `KEY_ALIAS` — 密钥别名（上例 `rpandroid`）
  - `KEY_PASSWORD` — 密钥口令
- 四个 secrets 齐全 → `VARIANT=release`（签名包）；全缺 → debug 签名兜底可直接安装；部分配置 → 显式失败。

### 生成 ANDROID_KEYSTORE（base64）

```bash
# Linux / macOS
base64 -w0 rpandroid-release.jks

# Windows（cmd）——用系统自带的 certutil，需去掉换行
certutil -encode rpandroid-release.jks rpandroid-release.b64
# 打开 rpandroid-release.b64，删除首行（-----BEGIN CERTIFICATE-----）、末行与所有空行，剩下的即 base64 内容

# Windows / 任意（若装 Git Bash 或 openssl）
openssl base64 -A -in rpandroid-release.jks
```

把输出粘贴为 `ANDROID_KEYSTORE` 的值（不带换行的单行）。

## 三、打 tag 触发正式发布

先手动跑一次 workflow（`workflow_dispatch`，未配 secrets 会产出 debug 包）确认全绿；配好四个 secrets 后再发正式：

```bash
git add -A && git commit -m "chore(release): v0.1.0"
git tag -a v0.1.0 -m "v0.1.0"
git push origin main --tags   # 推送 tag 即触发 build-release
```

Tag 格式为 `v*`，GitHub 会自动创建 Release「RPAuto v0.1.0」，附件含签名 APK 与 SHA256SUMS。
