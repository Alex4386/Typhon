#!/bin/bash
mvn package
cp ./target/typhon-*.jar ./.deploy/typhon.jar
cd .deploy
node index.js typhon.jar
rm typhon.jar
cd ..

