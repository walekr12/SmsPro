@rem Gradle startup script for Windows
@if "%DEBUG%"=="" @echo off
setlocal
set DIRNAME=%~dp0
set WRAPPER_JAR=%DIRNAME%\gradle\wrapper\gradle-wrapper.jar
if not exist "%WRAPPER_JAR%" (
    echo Downloading Gradle wrapper...
    mkdir "%DIRNAME%\gradle\wrapper" 2>nul
    powershell -Command "(New-Object Net.WebClient).DownloadFile('https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar', '%WRAPPER_JAR%')"
)
java -jar "%WRAPPER_JAR%" %*
