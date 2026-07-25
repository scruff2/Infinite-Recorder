#!/usr/bin/env sh
APP_HOME=$(cd "${0%/*}" && pwd -P)
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
else
    JAVACMD="java"
fi
if [ ! -x "$JAVACMD" ] ; then
    echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
    exit 1
fi
exec "$JAVACMD" $DEFAULT_JVM_OPTS -cp "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
