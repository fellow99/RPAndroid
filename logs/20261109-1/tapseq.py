import sys, time
sys.path.insert(0,'.')
from mcp import call
KEY = {
 'AC':(797,1650),'div':(1418,1650),
 '7':(797,1838),'8':(1004,1838),'9':(1211,1838),'mul':(1418,1838),
 '4':(797,2026),'5':(1004,2026),'6':(1211,2026),'sub':(1418,2026),
 '1':(797,2214),'2':(1004,2214),'3':(1211,2214),'add':(1418,2214),
 '00':(797,2400),'0':(1004,2400),'dot':(1211,2400),'eq':(1418,2400),
}
seq = sys.argv[1].split(',')
delay = float(sys.argv[2]) if len(sys.argv)>2 else 0.25
for k in seq:
    k=k.strip(); x,y = KEY[k]
    txt,err = call('screen_tap', {'x':x,'y':y})
    print(k, txt.strip()[:40])
    time.sleep(delay)
