#!/bin/bash

for f in $(find ./src/main -name '*.java'); do java -jar google-java-formatter.jar --aosp --fix-imports-only -r $f; done
