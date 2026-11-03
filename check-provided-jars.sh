#!/bin/sh
# Fail the build unless every `provided` jar in pom.xml is the exact version the target
# Horizon image ships in /horizon/lib.
#
# The hook is compiled against those jars but never ships them: Horizon's own copy is what
# runs. So when a HORIZON_VERSION bump moves one of them (2.12.0 moved tink 1.21.0 ->
# 1.24.0), the pom has to follow in the same change. This check is what makes that true,
# so a Renovate MR for the Horizon base cannot build until it does.
#
# usage: check-provided-jars.sh <`mvn dependency:list` output file> <horizon lib dir>
# The image names every jar <groupId>.<artifactId>-<version>.jar.
set -u
list=$1 lib=$2

[ -d "$lib" ] || { echo "ERROR: no Horizon lib dir at $lib"; exit 1; }
# "<groupId>.<artifactId> <version>" per provided dependency
want=$(sed -n 's/^ *\([^: ]*\):\([^: ]*\):jar:\([^: ]*\):provided.*/\1.\2 \3/p' "$list")
[ -n "$want" ] || { echo "ERROR: no provided jar in $list (is the listing broken?)"; exit 1; }

bad=0
echo "$want" | {
  while read -r name version; do
    jar="$name-$version.jar"
    if [ -f "$lib/$jar" ]; then
      echo "provided jar matches the image: $jar"
    else
      has=""
      for f in "$lib/$name"-[0-9]*.jar; do [ -e "$f" ] && has="$has ${f##*/}"; done
      echo "ERROR: pom.xml wants $jar; the image has:${has:- nothing}"
      bad=1
    fi
  done
  exit $bad
}
