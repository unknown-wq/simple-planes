import json, sys
# usage: views.py outdir prefix model.json [extra-offset-json...]
out, prefix, model = sys.argv[1], sys.argv[2], sys.argv[3]
m=[{"json":model}]
V = {
 "front34": dict(dir=[0.9,0.55,1.1], fit=0.72),
 "rear34":  dict(dir=[-0.9,0.6,-1.1], fit=0.72),
 "side":    dict(dir=[1,0,0], ortho=True, fit=0.62),
 "top":     dict(dir=[0,1,0], ortho=True, up=[0,0,1], fit=0.6),
 "front":   dict(dir=[0,0,1], ortho=True, fit=0.62),
 "below34": dict(dir=[0.8,-0.35,0.9], fit=0.72),
}
scenes=[]
for k,v in V.items():
    sc=dict(models=m, out=f"{out}/{prefix}-{k}.png", w=1200, h=800, **v)
    if k=="below34": sc["ground"]=False
    scenes.append(sc)
print(json.dumps(scenes))
