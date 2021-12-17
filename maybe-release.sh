#!/bin/bash
set -ev
if [ "$TRAVIS_BRANCH" == "master" ] && [ "$TRAVIS_PULL_REQUEST" == "false" ] && [ "$TRAVIS_REPO_SLUG" == "swisspush/gateleen" ]
then
    git reset --hard
    git clean -fd
    git checkout master
    echo 'Master checked out'
    groovy staging.groovy drop
    rc=$?
    if [ $rc -ne 0 ]
    then
      echo 'problem when trying to drop, ignored'
    fi
     echo 'starting a new nexus repository ...'
     OUTPUT=$(groovy staging.groovy start)
     echo "repository Id: $OUTPUT"
     mvn -B -Prelease -PpublicRepos -DskipTests jgitflow:release-start jgitflow:release-finish --settings settings.xml -DrepositoryId=${OUTPUT}
    rc=$?
    if [ $rc -eq 0 ]
    then
        groovy staging.groovy close ${OUTPUT}
        groovy staging.groovy promote ${OUTPUT}
        rc=$?
        if [ $rc -ne 0 ]
        then
          echo 'Release failed, cannot promote stage'
          exit rc
        fi
        echo 'Release done, will push'
        git tag
        git push --tags
        git checkout develop
        git push origin develop
      exit 0
    fi
    echo 'Release failed'
    exit rc
else
    echo 'Release skipped'
    exit 0
fi