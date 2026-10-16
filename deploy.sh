#!/bin/bash
# deploy.sh: build the jar and PUT it to Clojars (needs CLOJARS_USERNAME / CLOJARS_DEPLOY_TOKEN)
set -e
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-$HOME/sdk/jdk-17.0.20.1+1/Contents/Home}; export PATH=$JAVA_HOME/bin:$PATH
V=0.1.0; A=emaki-clj; P=io/github/hanshs474/$A
rm -rf build; mkdir -p build/jar/META-INF/maven/io.github.hanshs474/$A
cp -R src/emaki build/jar/
cp pom.xml build/jar/META-INF/maven/io.github.hanshs474/$A/pom.xml
(cd build/jar && jar cf ../$A-$V.jar .)
cp pom.xml build/$A-$V.pom
cat > build/maven-metadata.xml <<XML
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>io.github.hanshs474</groupId>
  <artifactId>$A</artifactId>
  <versioning>
    <release>$V</release>
    <versions>
      <version>$V</version>
    </versions>
    <lastUpdated>$(date -u +%Y%m%d%H%M%S)</lastUpdated>
  </versioning>
</metadata>
XML
# Checksums must not end in a newline, or Clojars rejects the metadata and burns the version.
for f in build/$A-$V.jar build/$A-$V.pom build/maven-metadata.xml; do
  printf '%s' "$(md5 -q $f)" > $f.md5; printf '%s' "$(shasum -a 1 $f | cut -d' ' -f1)" > $f.sha1
done
[ "$1" = "--dry" ] && { ls build; exit 0; }
set -a; source ~/.claude/.env; set +a
put() { curl -s -o /dev/null -w "%{http_code} $2\n" -u "$CLOJARS_USERNAME:$CLOJARS_DEPLOY_TOKEN" --upload-file "$1" "https://repo.clojars.org/$2"; }
for f in $A-$V.jar $A-$V.pom; do for x in "" .md5 .sha1; do put build/$f$x $P/$V/$f$x; done; done
for x in "" .md5 .sha1; do put build/maven-metadata.xml$x $P/maven-metadata.xml$x; done
