@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

if not exist "%CLASSPATH%" (
    where gradle >nul 2>&1
    if %ERRORLEVEL% equ 0 (
        gradle %*
        exit /b %ERRORLEVEL%
    )
    echo gradle-wrapper.jar not found. Open in Android Studio to sync or run "gradle wrapper".
    exit /b 1
)

java -Xmx64m -Xms64m "-Dorg.gradle.appname=gradlew.bat" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
