#!/bin/bash
set -ev
if [ "$TRAVIS_BRANCH" = "master" ] && [ "$TRAVIS_PULL_REQUEST" == "false" ] && [ "$TRAVIS_REPO_SLUG" == "ZhengXinCN/gateleen" ]
then
    git reset --hard
    git clean -fd
    git checkout master
    echo 'Master checked out'
    mvn -B -Prelease jgitflow:release-start jgitflow:release-finish --settings settings.xml
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
    exit rc
else
    echo 'Release skipped'
    exit 0
fi