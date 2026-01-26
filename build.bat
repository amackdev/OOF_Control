@echo off
setlocal

echo =========================================
echo    OOF Control - Android Build Script
echo =========================================

set SCRIPT_DIR=%~dp0
set WRAPPER_JAR=%SCRIPT_DIR%gradle\wrapper\gradle-wrapper.jar

:: Check Java
java -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java is not installed or not in PATH
    echo Please install JDK 17 or later
    pause
    exit /b 1
)

:: Check if gradle-wrapper.jar exists
if not exist "%WRAPPER_JAR%" (
    echo Gradle wrapper not found!
    echo Please download gradle-wrapper.jar from:
    echo https://github.com/gradle/gradle/raw/v8.2.0/gradle/wrapper/gradle-wrapper.jar
    echo.
    echo And place it in: %WRAPPER_JAR%
    pause
    exit /b 1
)

echo.

if "%1"=="clean" (
    call gradlew.bat clean
    goto :end
)

if "%1"=="debug" (
    call gradlew.bat assembleDebug
    echo.
    echo Debug APK location:
    echo %SCRIPT_DIR%app\build\outputs\apk\debug\app-debug.apk
    goto :end
)

if "%1"=="release" (
    call gradlew.bat assembleRelease
    echo.
    echo Release APK location:
    echo %SCRIPT_DIR%app\build\outputs\apk\release\app-release-unsigned.apk
    goto :end
)

if "%1"=="install" (
    call gradlew.bat installDebug
    goto :end
)

:: Default: build debug
echo Usage: build.bat [clean^|debug^|release^|install]
echo.
echo Commands:
echo   clean   - Clean build files
echo   debug   - Build debug APK
echo   release - Build release APK (unsigned)
echo   install - Build and install debug APK to connected device
echo.
echo Building debug APK by default...
echo.

call gradlew.bat assembleDebug

echo.
echo Debug APK location:
echo %SCRIPT_DIR%app\build\outputs\apk\debug\app-debug.apk

:end
pause
