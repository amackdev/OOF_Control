#!/bin/bash

# OOF Control - Build Script
# This script helps build the APK

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER_JAR="$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar"
WRAPPER_URL="https://services.gradle.org/distributions/gradle-8.2-bin.zip"

echo "========================================="
echo "   OOF Control - Android Build Script"
echo "========================================="

# Check if Java is available
if ! command -v java &> /dev/null; then
    echo "ERROR: Java is not installed or not in PATH"
    echo "Please install JDK 17 or later"
    exit 1
fi
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
# Check Java version
JAVA_VER=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
echo "Java version: $JAVA_VER"

if [ "$JAVA_VER" -lt 17 ]; then
    echo "WARNING: Java 17+ is recommended for Android builds"
fi

# Check if gradle-wrapper.jar exists, if not download it
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Gradle wrapper not found, downloading..."
    mkdir -p "$SCRIPT_DIR/gradle/wrapper"
    
    # Try to download from Gradle's release
    if command -v curl &> /dev/null; then
        curl -sL "https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar" -o "$WRAPPER_JAR"
    elif command -v wget &> /dev/null; then
        wget -q "https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar" -O "$WRAPPER_JAR"
    else
        echo "ERROR: Neither curl nor wget found. Please download gradle-wrapper.jar manually."
        echo "URL: https://github.com/gradle/gradle/raw/v8.2.0/gradle/wrapper/gradle-wrapper.jar"
        echo "Place it in: $WRAPPER_JAR"
        exit 1
    fi
    
    if [ ! -f "$WRAPPER_JAR" ] || [ ! -s "$WRAPPER_JAR" ]; then
        echo "ERROR: Failed to download gradle-wrapper.jar"
        echo "Please download it manually from:"
        echo "https://github.com/gradle/gradle/raw/v8.2.0/gradle/wrapper/gradle-wrapper.jar"
        exit 1
    fi
    
    echo "Gradle wrapper downloaded successfully"
fi

# Make gradlew executable
chmod +x "$SCRIPT_DIR/gradlew"

# Run the build
echo ""
echo "Starting build..."
echo ""

cd "$SCRIPT_DIR"

case "$1" in
    "clean")
        ./gradlew clean
        ;;
    "debug")
        ./gradlew assembleDebug
        echo ""
        echo "Debug APK location:"
        echo "$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
        ;;
    "release")
        ./gradlew assembleRelease
        echo ""
        echo "Release APK location:"
        echo "$SCRIPT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
        ;;
    "install")
        ./gradlew installDebug
        ;;
    *)
        echo "Usage: $0 [clean|debug|release|install]"
        echo ""
        echo "Commands:"
        echo "  clean   - Clean build files"
        echo "  debug   - Build debug APK"
        echo "  release - Build release APK (unsigned)"
        echo "  install - Build and install debug APK to connected device"
        echo ""
        echo "Building debug APK by default..."
        ./gradlew assembleDebug
        echo ""
        echo "Debug APK location:"
        echo "$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
        ;;
esac
