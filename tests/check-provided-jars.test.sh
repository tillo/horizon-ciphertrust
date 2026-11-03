#!/bin/sh
# Tests for check-provided-jars.sh: run it against a fake `mvn dependency:list` output
# and a fake /horizon/lib, the way the Dockerfile's build stage runs it.
# usage: sh tests/check-provided-jars.test.sh   (exit 0 = all pass)
set -u
here=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fails=0

lib() { rm -rf "${tmp:?}/lib"; mkdir "$tmp/lib"; for j in "$@"; do : > "$tmp/lib/$j"; done; }
deps() { printf '\nThe following files have been resolved:\n' > "$tmp/deps.txt"; printf '   %s\n' "$@" >> "$tmp/deps.txt"; }
run() { sh "$here/check-provided-jars.sh" "$tmp/deps.txt" "$tmp/lib" > "$tmp/out" 2>&1; echo $?; }
expect() { # <name> <want rc> <got rc> [text the output must contain]
  if [ "$2" != "$3" ] || { [ $# -gt 3 ] && ! grep -qF -- "$4" "$tmp/out"; }; then
    echo "FAIL $1 (rc want $2 got $3)"; sed 's/^/    /' "$tmp/out"; fails=$((fails+1))
  else echo "ok   $1"; fi
}

# Horizon 2.10.2's real lib names (the image ships every jar as <groupId>.<artifactId>-<version>.jar).
lib_2102() { lib com.google.crypto.tink.tink-1.21.0.jar com.google.crypto.tink.tink-awskms-2.0.0.jar \
  org.scala-lang.scala-library-2.13.18.jar org.slf4j.slf4j-api-2.0.18.jar fr.evertrust.horizon-2.10.2.jar; }

lib_2102
deps "com.google.crypto.tink:tink:jar:1.21.0:provided -- module com.google.crypto.tink" \
     "org.scala-lang:scala-library:jar:2.13.18:provided -- module scala.library [auto]" \
     "org.slf4j:slf4j-api:jar:2.0.18:provided -- module org.slf4j" \
     "org.javassist:javassist:jar:3.30.2-GA:compile -- module org.javassist"
expect "every provided jar is in the image" 0 "$(run)" "provided jar matches the image: org.slf4j.slf4j-api-2.0.18.jar"

deps "com.google.crypto.tink:tink:jar:1.21.0:provided" "org.scala-lang:scala-library:jar:2.13.14:provided"
expect "a drifted provided jar fails and names what the image has" 1 "$(run)" \
       "org.scala-lang.scala-library-2.13.14.jar; the image has: org.scala-lang.scala-library-2.13.18.jar"

# Horizon 2.12.0 moved tink 1.21.0 -> 1.24.0: a HORIZON_VERSION bump alone must not build.
lib com.google.crypto.tink.tink-1.24.0.jar org.slf4j.slf4j-api-2.0.20.jar
deps "com.google.crypto.tink:tink:jar:1.21.0:provided" "org.slf4j:slf4j-api:jar:2.0.18:provided"
expect "a Horizon bump that moves tink fails" 1 "$(run)" "com.google.crypto.tink.tink-1.24.0.jar"

lib_2102
deps "com.google.crypto.tink:tink:jar:1.20.0:provided"
run > /dev/null
if grep -q awskms "$tmp/out"; then echo "FAIL the hint names only the same artifact, not tink-awskms"; fails=$((fails+1))
else echo "ok   the hint names only the same artifact, not tink-awskms"; fi

deps "org.foo:bar:jar:1.0:provided"
expect "a provided jar the image does not ship at all fails" 1 "$(run)" "the image has: nothing"

deps "org.javassist:javassist:jar:3.30.2-GA:compile"
expect "no provided jar listed means the listing is broken: fail closed" 1 "$(run)" "no provided"

: > "$tmp/deps.txt"; rm -rf "${tmp:?}/lib"
expect "a missing lib dir fails" 1 "$(run)"

if [ $fails -ne 0 ]; then echo "$fails failed"; exit 1; fi
echo "all passed"
