#!/bin/bash

if [ -n "$CLAUDE_CODE_REMOTE" ]; then
  mkdir -p ~/.gradle
  if [ -n "$https_proxy" ]; then
    PROXY_HOST=$(echo $https_proxy | sed -E 's/.*:\/\/([^:]+).*/\1/')
    PROXY_PORT=$(echo $https_proxy | sed -E 's/.*:([0-9]+).*/\1/')
    cat <<EOF > ~/.gradle/gradle.properties
systemProp.http.proxyHost=$PROXY_HOST
systemProp.http.proxyPort=$PROXY_PORT
systemProp.https.proxyHost=$PROXY_HOST
systemProp.https.proxyPort=$PROXY_PORT
jdk.http.auth.tunneling.disabledSchemes=
jdk.http.auth.proxying.disabledSchemes=
EOF
  fi
fi