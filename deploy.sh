#!/bin/bash
set -e

# ====== common variables ======
DEPLOY_METHOD="minehub"  # either minehub or ssh
SSH_HOSTNAME=""
SSH_DEPLOY_TARGET=""

# try to import .env file if exists
if [ -f ".env" ]; then
  source .env
fi

./gradlew build
mkdir -p .deploy
cp ./build/libs/typhon-*.jar ./.deploy/typhon.jar

if [ "$DEPLOY_METHOD" = "minehub" ]; then
  cd .deploy
  node index.js typhon.jar
  rm typhon.jar
  cd ..
elif [ "$DEPLOY_METHOD" = "ssh" ]; then
  scp ./.deploy/typhon.jar "$SSH_HOSTNAME:$SSH_DEPLOY_TARGET"
  rm ./.deploy/typhon.jar
fi
