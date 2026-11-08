import json, sys, urllib.request
cfg = json.load(open('D:/android-workspace/AndroidStudioProjects/ManagedFloatingApp/.pi/mcp.json'))['mcpServers']['ManagedFloatingApp']
base = cfg['url'].rsplit('/mcp',1)[0]
def api(path, method='GET', body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base+'/'+path.lstrip('/'), data=data, method=method,
        headers={'Content-Type':'application/json','Authorization':cfg['headers']['Authorization']})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode()
if __name__ == '__main__':
    print(api(sys.argv[1], sys.argv[2] if len(sys.argv)>2 else 'GET',
              json.loads(sys.argv[3]) if len(sys.argv)>3 else None)[:3000])
