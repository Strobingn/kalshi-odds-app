import json
r=json.load(open('report54.json'))
lines=[];cfg=[]
for c in ['BTC','ETH','SOL']:
    for H,w in r[c]['weights'].items():
        lines.append('        "%s|%s" to doubleArrayOf(%s),'%(c,-1 if H=='close' else H,", ".join(repr(x) for x in w)))
    h,m=r[c]['sel']['fc54|ride']['picks'][-1].split('/')
    cfg.append('"%s" to Config(%s, %s)'%(c,-1 if h=='close' else h,m))
t=open('F54.tmpl').read().replace('@@W@@',"\n".join(lines)).replace('@@CFG@@',", ".join(cfg))
open('/workspace/kalshi-odds-app-kashi/app/src/main/java/com/dirk/kalshiodds/decision/LeadForecaster.kt','w').write(t); print(cfg)
