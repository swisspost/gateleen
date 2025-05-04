#!/usr/bin/env bash
docker build -t guentherm/gateleen:$1 .
docker tag guentherm/gateleen:$1 guentherm/gateleen:latest
docker push guentherm/gateleen:$1


