#!/bin/bash
set -ev
git fetch
git reset --hard
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