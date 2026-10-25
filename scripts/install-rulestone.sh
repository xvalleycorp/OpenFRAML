#!/usr/bin/env bash
# Installs RuleStone (https://github.com/xvalleycorp/RuleStone, Apache-2.0) into the local Maven repository from
# source, at a pinned commit. RuleStone is not on Maven Central yet; once it is, delete this script and set
# rulestone.version in pom.xml to the release.
#
# A commit id is git's hash of the content: the build is reproducible, and the content cannot change under the same
# id. To move to another commit: change RULESTONE_COMMIT, run the script again, then mvn verify.
set -euo pipefail

RULESTONE_REPO="https://github.com/xvalleycorp/RuleStone.git"
RULESTONE_COMMIT="5a4dc73dde760501b993de38feccf1dd2004d847"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Installing RuleStone at commit $RULESTONE_COMMIT"
git -C "$work" init -q
git -C "$work" fetch -q --depth 1 "$RULESTONE_REPO" "$RULESTONE_COMMIT"
git -C "$work" checkout -q FETCH_HEAD
# Only the modules OpenFRAML uses. RuleStone's own tests run in RuleStone's CI.
mvn -B -q -f "$work/pom.xml" -pl rulestone-core,rulestone-test -am -DskipTests install
