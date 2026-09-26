#!/bin/bash
# usage: iter.sh <round-name>   -> dumps (idle) and renders all views + sheet into out/<round>
set -e
F=/tmp/claude-0/-home-user-minecolonies-fabric/32776c26-3535-4748-aeb8-06e05c1c6bcf/scratchpad/fighter
cd $F
(cd gen && python3 gen.py) >/dev/null && cp /home/user/simple-planes/26.2/src/main/resources/assets/simpleplanes/textures/plane_upgrades/fighter_metal.png tex/
./dump.sh
python3 views.py out/$1 fighter fighter.json > scenes_$1.json
node render.mjs scenes_$1.json > /dev/null
python3 - "$1" <<'PY'
import sys
from PIL import Image
r=sys.argv[1]
ims=[Image.open(f'out/{r}/fighter-{k}.png') for k in ['front34','rear34','side','top','front','below34']]
W,H=ims[0].size; sheet=Image.new('RGB',(W*3,H*2))
for i,im in enumerate(ims): sheet.paste(im,((i%3)*W,(i//3)*H))
sheet.resize((W*3//2,H)).save(f'out/{r}/sheet.png')
PY
echo rendered out/$1
