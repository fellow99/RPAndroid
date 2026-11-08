import json, sys
sys.path.insert(0,'.')
from mcp import call
script = """await host.invoke('log', { msg: 'run start' });
await host.invoke('screen.launch', { pkg: 'com.miui.calculator' });
await new Promise(r => setTimeout(r, 1500));
await host.invoke('screen.tap', { x: 797, y: 1650 });
await new Promise(r => setTimeout(r, 100));
for (let i = 0; i < 10; i++) {
  const r = await host.invoke('screen.tap', { x: 797, y: 2214 });
  if (!r.ok) await host.invoke('log', { msg: 'tap ' + i + ' failed' });
  await new Promise(r => setTimeout(r, 30));
}
await host.invoke('log', { msg: 'run done' });
"""
task = {
  "id": "test-seq-key",
  "name": "测试连续按键",
  "packageName": "com.miui.calculator",
  "script": script,
  "enabled": False,
  "kind": "CRON",
  "intervalMs": 0,
  "cron": "* * * * *",
}
txt, err = call('task_create', {'task': task})
print('create:', txt, err)
txt, err = call('task_list')
print('list:', txt[:800])
