#!/bin/bash
# ============================================================
#  PDMS 数据导入工具 - macOS / Linux 启动脚本
#
#  用法：
#    ./run.sh                      自动查找 JDK
#    ./run.sh /opt/jdk-21          指定 JDK 安装目录
#    ./run.sh --jdk /opt/jdk-17    同上
#    ./run.sh --help               查看帮助
#
#  JDK 查找优先级：
#    命令行参数 > 环境变量 PDMS_JAVA_HOME > 同目录 jdk-path.txt > JAVA_HOME > PATH 中的 java
#  前三种为"显式指定"，路径无效时直接报错退出，不会悄悄改用其它版本。
#
#  要求：JDK 21 及以上（最低 21）
# ============================================================

set -e

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------- 解析命令行参数 ----------
JDK_ARG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --jdk|-j)
      JDK_ARG="${2:-}"
      shift 2
      ;;
    --help|-h)
      echo "用法：$0 [JDK安装目录]  或  $0 --jdk <JDK安装目录>"
      echo "也可把 JDK 安装目录写进 $APP_DIR/jdk-path.txt"
      exit 0
      ;;
    *)
      JDK_ARG="$1"
      shift
      ;;
  esac
done

# ---------- 定位 jar ----------
JAR_PATH="$APP_DIR/pdms-import-tool.jar"
if [ ! -f "$JAR_PATH" ]; then
  JAR_PATH="$APP_DIR/target/pdms-import-tool.jar"
fi

if [ ! -f "$JAR_PATH" ] && [ -f "$APP_DIR/pom.xml" ]; then
  echo "[提示] 未找到 jar，尝试自动打包..."
  if ! command -v mvn >/dev/null 2>&1; then
    echo "[错误] 未找到 Maven，且当前目录没有 jar。请使用发布包中的完整文件。"
    exit 1
  fi
  (cd "$APP_DIR" && mvn -o -q package -DskipTests)
  JAR_PATH="$APP_DIR/target/pdms-import-tool.jar"
fi

if [ ! -f "$JAR_PATH" ]; then
  echo "[错误] 未找到 pdms-import-tool.jar，请确认解压完整：$APP_DIR"
  exit 1
fi

# ---------- 读取 jdk-path.txt（忽略空行与 # 注释行）----------
JDK_FROM_FILE=""
if [ -f "$APP_DIR/jdk-path.txt" ]; then
  JDK_FROM_FILE=$(grep -v '^[[:space:]]*#' "$APP_DIR/jdk-path.txt" 2>/dev/null \
    | grep -v '^[[:space:]]*$' | head -n 1 | tr -d '\r' \
    | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
fi

# ---------- 定位 java ----------
# 使用一个显式指定的 JDK 目录；无效时报错返回 1
use_jdk() {
  local source="$1"
  local dir="$2"
  if [ -z "$dir" ]; then
    return 1
  fi
  if [ -x "$dir/bin/java" ]; then
    JAVA_CMD="$dir/bin/java"
    echo "[信息] 使用 JDK（$source）：$dir"
    return 0
  fi
  echo "[错误] $source 指定的 JDK 路径无效，未找到可执行文件：$dir/bin/java"
  echo "       该路径应指向 JDK 安装目录（其下应有 bin/java）"
  return 1
}

JAVA_CMD=""
if [ -n "$JDK_ARG" ]; then
  use_jdk "命令行参数" "$JDK_ARG" || exit 1
elif [ -n "$PDMS_JAVA_HOME" ]; then
  use_jdk "环境变量 PDMS_JAVA_HOME" "$PDMS_JAVA_HOME" || exit 1
elif [ -n "$JDK_FROM_FILE" ]; then
  use_jdk "$APP_DIR/jdk-path.txt" "$JDK_FROM_FILE" || exit 1
elif [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
  echo "[信息] 使用 JDK（环境变量 JAVA_HOME）：$JAVA_HOME"
elif command -v java >/dev/null 2>&1; then
  JAVA_CMD="java"
  echo "[信息] 使用 PATH 中的 java：$(command -v java)"
else
  echo "[错误] 未检测到 Java 运行环境，请安装 JDK 21 及以上版本"
  echo "       也可在 $APP_DIR/jdk-path.txt 中填写 JDK 安装目录，或用 $0 <JDK目录> 指定"
  exit 1
fi

echo "[信息] Java 版本：$("$JAVA_CMD" -version 2>&1 | head -n 1)"
echo "[信息] 正在启动 PDMS 数据导入工具..."
exec "$JAVA_CMD" -Dfile.encoding=UTF-8 -Xmx1g -jar "$JAR_PATH"
