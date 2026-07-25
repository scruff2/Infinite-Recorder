@ECHO OFF
SET APP_HOME=%~dp0
SET DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
SET CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
IF NOT "%JAVA_HOME%" == "" (
  SET JAVACMD=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVACMD=java
)
%JAVACMD% %DEFAULT_JVM_OPTS% -cp "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
