#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT="/home/studs/s505225/httpd-root"
HTTPD="/usr/local/sbin/httpd"
JAVA=${JAVA:-java}

"$PROJECT_DIR/scripts/build.sh"

mkdir -p "$ROOT/public/lab2" "$ROOT/fcgi-bin" "$ROOT/mutex-dir"
cp "$PROJECT_DIR/public/index.html" "$ROOT/public/lab2/index.html"
cp "$PROJECT_DIR/public/style.css" "$ROOT/public/lab2/style.css"
cp "$PROJECT_DIR/public/script.js" "$ROOT/public/lab2/script.js"
cp "$PROJECT_DIR/build/lab2.jar" "$ROOT/fcgi-bin/lab2.jar"
cp "$PROJECT_DIR/lib/fastcgi-lib.jar" "$ROOT/fcgi-bin/fastcgi-lib.jar"
cp "$PROJECT_DIR/httpd.conf" "$ROOT/httpd.conf"

if [ -f "$ROOT/fastcgi.pid" ]; then
    OLD_PID=$(cat "$ROOT/fastcgi.pid" 2>/dev/null || true)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        kill "$OLD_PID"
        sleep 1
    fi
fi

nohup "$JAVA" -DFCGI_PORT=25026 \
    -cp "$ROOT/fcgi-bin/fastcgi-lib.jar:$ROOT/fcgi-bin/lab2.jar" \
    app.Main > "$ROOT/fastcgi.log" 2>&1 &
echo $! > "$ROOT/fastcgi.pid"

if [ -f "$ROOT/httpd.pid" ]; then
    "$HTTPD" -f "$ROOT/httpd.conf" -k stop 2>/dev/null || true
    sleep 1
fi
"$HTTPD" -f "$ROOT/httpd.conf" -k start

echo "Лабораторная запущена: http://helios.cs.ifmo.ru:25025/lab2/"
