#!/bin/bash
# Fast iteration: javac the fighter models into scratch (the Gradle build is the final check), then dump.
set -e
F=/tmp/claude-0/-home-user-minecolonies-fabric/32776c26-3535-4748-aeb8-06e05c1c6bcf/scratchpad/fighter
J=/usr/lib/jvm/java-25-openjdk-amd64/bin
SRC=/home/user/simple-planes/26.2/src/main/java/xyz/przemyk/simpleplanes/client/render/models
CP="$(cat $F/dumper/cp.txt)"
rm -rf $F/classes && mkdir -p $F/classes
$J/javac -nowarn -cp "$CP" -d $F/classes $SRC/FighterModel.java $SRC/FighterMetalModel.java $SRC/FighterExhaustModel.java 2>&1 | grep -v "Picked up" || true
M=xyz.przemyk.simpleplanes.client.render.models
cd $F/dumper
$J/java -cp "$F/classes:.:$CP" ModelDump $F/fighter.json ${TX:-0} ${TY:-0} ${TZ:-0.25} wood=$M.FighterModel=oak_planks metal=$M.FighterMetalModel=fighter_metal exhaust=$M.FighterExhaustModel=fighter_metal=${THROTTLE:-0} 2>&1 | grep -v "Picked up"
