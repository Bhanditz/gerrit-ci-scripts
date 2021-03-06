#!/bin/bash -e

git read-tree -u --prefix=gerrit gerrit/{gerrit-branch}
. set-java.sh 8

if [ -f "gerrit/BUILD" ]
then
  pushd gerrit
  bazel build api
  ./tools/maven/api.sh install
  popd
fi

sbt -no-colors compile test assembly

# Extract version information
PLUGIN_JARS=$(find . -name '{name}*jar')
for jar in $PLUGIN_JARS
do
  PLUGIN_VERSION=$(git describe  --always origin/{branch})
  echo -e "Implementation-Version: $PLUGIN_VERSION" > MANIFEST.MF
  jar ufm $jar MANIFEST.MF && rm MANIFEST.MF

  echo "$PLUGIN_VERSION" > $jar-version

  curl -L https://gerrit-review.googlesource.com/projects/plugins%2F{name}/config | \
     tail -n +2 > $(dirname $jar)/$(basename $jar .jar).json
done
