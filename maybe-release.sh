#!/bin/bash
set -ev

# Ensure we have the complete history including all branches and tags
git fetch --all --tags --prune

# Detach HEAD to allow updating branch refs
git checkout --detach

# Now safely fetch branches
git fetch origin master:master
git fetch origin develop:develop

# Reset to a clean state on master
git checkout master
git reset --hard origin/master

# Run the release
mvn -B -Prelease -PpublicRepos jgitflow:release-start jgitflow:release-finish -DskipTests=true
rc=$?
if [ $rc -eq 0 ]
then
    echo 'Release done, will push'
    git tag
    git push --tags
    git checkout develop
    git push origin develop
  exit 0
fi
echo 'Release failed'
exit $rc