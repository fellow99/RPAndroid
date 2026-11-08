# MJPEG 视频流接入文档

> 面向第三方：实时查看 ManagedFloatingApp 虚拟屏画面。
> 相关文档：[REST API](./API.md) ｜ [MCP 接入](./MCP.md)

## 1. 端点

```
GET http://<手机IP>:8099/stream
```

- 响应类型：`multipart/x-mixed-replace; boundary=frame`
- 每一帧是一张完整 PNG，以 `--frame` 分隔持续推送。
- 默认约每 200ms 推送一帧（虚拟屏 720×1560，RGBA 采集后编码为 PNG）。
- 同时在线客户端上限 **8**（`MAX_STREAM_CLIENTS`），超出返回 HTTP `503`。
- 静态画面无新帧时，服务端复用最后一帧，保证连接持续有数据。

网络接入（局域网 / `adb forward`）见 [API.md §1](./API.md#1-网络接入)。

## 2. 鉴权

三种方式任选：

```bash
# 请求头
curl -H "Authorization: Bearer <token>" http://localhost:8099/stream

# 自定义头
curl -H "X-Auth-Token: <token>" http://localhost:8099/stream

# 查询参数（浏览器最方便）
http://localhost:8099/stream?token=<token>
```

未通过鉴权返回 `401`，并带 `WWW-Authenticate: Bearer realm=ManagedFloatingApp`。

## 3. 使用方式

### 浏览器
直接打开：
```
http://localhost:8099/stream?token=<token>
```

### curl 保存为 MJPEG 文件
```bash
curl -H "Authorization: Bearer <token>" \
  http://localhost:8099/stream -o stream.mjpeg
```

### ffplay / VLC
```bash
ffplay -headers "Authorization: Bearer <token>" http://localhost:8099/stream
```
VLC：媒体 → 打开网络串流 → 输入 `http://localhost:8099/stream?token=<token>`。

### HTML 内嵌
```html
<img src="http://<手机IP>:8099/stream?token=<token>" alt="live screen" />
```

### Python 拉流（按 boundary 拆帧）
```python
import requests

r = requests.get(
    "http://localhost:8099/stream",
    headers={"Authorization": "Bearer <token>"},
    stream=True,
    timeout=30,
)
buf = b""
for chunk in r.iter_content(8192):
    buf += chunk
    while b"\xff\xd8" in buf:  # 这里实际为 PNG，可按 --frame boundary 拆分
        pass
```
> 实际帧为 PNG（魔数 `\x89PNG`），生产代码建议按 `--frame` 分隔与 `Content-Length` 解析。

## 4. 前置条件

视频流依赖已建立的托管会话（虚拟屏 + shell 助手）：

```bash
curl -X POST http://localhost:8099/debug/session/start \
  -H 'Content-Type: application/json' \
  -d '{"connectPort":<adbd端口>}'
```

确认 `GET /status` 返回 `sessionActive=true`。会话未就绪时连接可能拿不到有效帧。

## 5. 说明与限制

- WebRTC 未实现，当前仅支持 MJPEG-over-HTTP。
- MJPEG 适合局域网/USB 场景；不要暴露到公网。
- 每路流占用一个服务端阻塞处理线程，因此设置 8 路并发上限保护设备资源。
- 若需要单张截图而非持续视频，使用 REST `GET /frame`（见 [API.md](./API.md)）。
