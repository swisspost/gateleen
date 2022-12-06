#!/bin/bash
set -ev
git fetch
git reset --hard
groovy staging.groovy drop
rc=$?
if [ $rc -ne 0 ]
then
  echo 'problem when trying to drop, ignored'
fi
echo 'starting a new nexus repository ...'
OUTPUT=$(groovy staging.groovy start)
echo "repository Id: $OUTPUT"
mvn -B -Prelease -PpublicRepos jgitflow:release-start jgitflow:release-finish -DrepositoryId=${OUTPUT}
rc=$?
if [ $rc -eq 0 ]
then
    groovy staging.groovy close ${OUTPUT}
    groovy staging.groovy promote ${OUTPUT}
    rc=$?
    if [ $rc -ne 0 ]
    then
      echo 'Release failed, cannot promote stage'
      exit $rc
    fi
    echo 'Release done, will push'
    git tag
    git push --tags
    git checkout develop
    git push origin develop
  exit 0
fi
echo 'Release failed'
exit $rc