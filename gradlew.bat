@echo off
set APP_HOME=%~dp0

set JAVA_CMD=java
if not "%JAVA_HOME%"=="" set JAVA_CMD=%JAVA_HOME%\bin\java.exe

"%JAVA_CMD%" -Dorg.gradle.appname=gradlew -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
