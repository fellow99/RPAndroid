const info = await host.invoke('screen.info', {});
await host.invoke('log', { msg: 'run start screen=' + info.w + 'x' + info.h });
await host.invoke('screen.launch', { pkg: 'com.miui.calculator' });
await new Promise(r => setTimeout(r, 1500));
const sx = (info.w || 1600) / 1600, sy = (info.h || 2560) / 2560;
await host.invoke('screen.tap', { x: Math.round(797 * sx), y: Math.round(1650 * sy) });
await new Promise(r => setTimeout(r, 100));
for (let i = 0; i < 10; i++) {
  const r = await host.invoke('screen.tap', { x: Math.round(797 * sx), y: Math.round(2214 * sy) });
  if (!r.ok) await host.invoke('log', { msg: 'tap ' + i + ' failed' });
  await new Promise(r => setTimeout(r, 30));
}
await host.invoke('log', { msg: 'run done' });
