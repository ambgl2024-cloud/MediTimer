#!/bin/sh
APP_HOME=$(cd "${0%/*}" >/dev/null 2>&1 && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
CLASSPATH="$WRAPPER_JAR"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Gradle wrapper JAR non presente: download della versione ufficiale 8.9..."
  command -v curl >/dev/null 2>&1 || { echo "curl non disponibile. Installa Gradle 8.9 e lancia: gradle wrapper --gradle-version 8.9"; exit 1; }
  curl -L --fail -o "$WRAPPER_JAR" "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar" || exit 1
fi
if [ -n "$JAVA_HOME" ]; then JAVACMD="$JAVA_HOME/bin/java"; else JAVACMD=java; fi
exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
