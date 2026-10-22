#!/usr/bin/env bash
#
# 构建 pvz-quick-cheats 模组 jar（需要用 JDK 25，因为游戏字节码是 Java 25）。
#
# 用法：
#   ./build.sh [游戏目录] [javac 路径]
#
#   游戏目录默认按 ../PvZ-Portable-1.2.5 查找，需包含 PvZ-Portable-1.2.5.jar。
#   不传 javac 时依次使用 $JAVA_HOME/bin/javac、PATH 上的 javac。
#
# 编译期依赖（fabric-loader、api-base、api-events）已随工程放在 libs/ 目录，构建无需联网。

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    "$HERE/src/main/resources/fabric.mod.json" | head -1)"

GAME_DIR="${1:-$HERE/../PvZ-Portable-1.2.5}"
JAVAC="${2:-}"
if [ -z "$JAVAC" ]; then
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        JAVAC="$JAVA_HOME/bin/javac"
    else
        JAVAC="javac"
    fi
fi
JAR="${JAVAC%/javac}/jar"
[ -x "$JAR" ] || JAR="jar"

GAME_JAR=""
for candidate in "$GAME_DIR"/*.jar; do
    [ -f "$candidate" ] || continue
    case "$(basename "$candidate")" in
        *fabric* | *api*) continue ;;
    esac
    GAME_JAR="$candidate"
    break
done
if [ -z "$GAME_JAR" ]; then
    echo "找不到游戏 jar（PvZ-Portable-*.jar）：请把游戏目录作为第 1 个参数传入。" >&2
    exit 1
fi

CP="$GAME_JAR"
for lib in "$HERE"/libs/*.jar; do
    [ -f "$lib" ] || continue
    CP="$CP:$lib"
done

echo "javac     : $("$JAVAC" -version 2>&1)"
echo "游戏 jar  : $GAME_JAR"
echo "classpath : $CP"

OUT="$HERE/build"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

SOURCES=()
while IFS= read -r file; do SOURCES+=("$file"); done < <(find "$HERE/src/main/java" -name '*.java')

"$JAVAC" --release 25 -encoding UTF-8 -Xlint:all -cp "$CP" -d "$OUT/classes" "${SOURCES[@]}"
cp "$HERE/src/main/resources/fabric.mod.json" "$OUT/classes/fabric.mod.json"

cat > "$OUT/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
Implementation-Title: PvZ Quick Cheats
Implementation-Version: $VERSION
Fabric-Mod-Id: pvz-quick-cheats

EOF

"$JAR" --create --file "$HERE/pvz-quick-cheats-$VERSION.jar" \
    --manifest "$OUT/MANIFEST.MF" -C "$OUT/classes" .

echo "已生成：$HERE/pvz-quick-cheats-$VERSION.jar"