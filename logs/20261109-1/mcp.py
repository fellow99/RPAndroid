#!/usr/bin/env python3
"""MCP test helper (no secrets written to artifacts)."""
import base64, json, sys, urllib.request, os

CFG = 'D:/android-workspace/AndroidStudioProjects/ManagedFloatingApp/.pi/mcp.json'
cfg = json.load(open(CFG))['mcpServers']['ManagedFloatingApp']
URL, AUTH = cfg['url'], cfg['headers']['Authorization']

def call(name, args=None, timeout=120):
    body = json.dumps({"jsonrpc":"2.0","id":1,"method":"tools/call",
                       "params":{"name":name,"arguments":args or {}}}).encode()
    req = urllib.request.Request(URL, data=body, headers={'Content-Type':'application/json','Authorization':AUTH})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        d = json.loads(r.read().decode())
    res = d.get('result', {})
    txt = res.get('content', [{}])[0].get('text', '')
    return txt, res.get('isError', False)

def shot(path, name='screen_capture', args=None):
    txt, err = call(name, args)
    if err or txt.startswith('error') or txt == 'no frame':
        print('CAPTURE FAIL:', txt[:200]); return None
    open(path, 'wb').write(base64.b64decode(txt))
    print('saved', path, os.path.getsize(path), 'bytes')
    return path

if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'shot':
        shot(sys.argv[2])
    elif cmd == 'call':
        args = json.loads(sys.argv[3]) if len(sys.argv) > 3 else {}
        txt, err = call(sys.argv[2], args)
        print(('[ERR] ' if err else '') + txt[:4000])
    elif cmd == 'raw':
        txt, err = call(sys.argv[2], json.loads(sys.argv[3]) if len(sys.argv) > 3 else {})
        print(txt)
