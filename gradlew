#!/bin/sh

APP_HOME=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd -P)

JAVA_CMD=java
if [ -n "$JAVA_HOME" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
fi

exec "$JAVA_CMD" \
  -Dorg.gradle.appname=gradlew \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
