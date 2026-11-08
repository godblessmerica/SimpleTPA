#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
cd "$project_dir"
mkdir -p .tools/test-home tmp
export MAVEN_OPTS="${MAVEN_OPTS:-} -Djava.io.tmpdir=$project_dir/tmp -Duser.home=$project_dir/.tools"
if command -v mvn >/dev/null 2>&1; then
    maven_command=mvn
elif [ -x .tools/apache-maven-3.9.11/bin/mvn ]; then
    maven_command=.tools/apache-maven-3.9.11/bin/mvn
else
    echo 'Install Maven 3.9+ and Java 25+, then run ./build.sh again.' >&2
    exit 1
fi
exec "$maven_command" -B -ntp "-Dmaven.repo.local=$project_dir/.tools/m2" "-DargLine=-Djava.io.tmpdir=$project_dir/tmp -Duser.home=$project_dir/.tools/test-home" package "$@"
