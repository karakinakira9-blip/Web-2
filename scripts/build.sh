#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD_DIR="$PROJECT_DIR/build"
LIBRARY="$PROJECT_DIR/lib/fastcgi-lib.jar"

if [ ! -f "$LIBRARY" ]; then
    echo "Не найден $LIBRARY" >&2
    echo "Скопируйте fastcgi-lib.jar из материалов лабораторной в папку lib." >&2
    exit 1
fi

rm -rf "$BUILD_DIR/classes"
mkdir -p "$BUILD_DIR/classes"

find "$PROJECT_DIR/src" -name '*.java' -print > "$BUILD_DIR/sources.txt"
javac -encoding UTF-8 -cp "$LIBRARY" -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
jar --create --file "$BUILD_DIR/lab2.jar" --main-class app.Main -C "$BUILD_DIR/classes" .

echo "Собран $BUILD_DIR/lab2.jar"
