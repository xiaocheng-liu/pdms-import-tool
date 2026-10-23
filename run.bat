@echo off
setlocal

rem 切到 UTF-8 代码页，保证中文提示与程序日志不乱码
chcp 65001 >nul
cd /d "%~dp0"
title PDMS 数据导入工具

rem 可选：把 JDK 安装目录写进 jdk-path.txt（一行，# 开头为注释）；不配则使用 PATH 里的 java
set "JAVA_HOME_DIR="
if exist "jdk-path.txt" for /f "usebackq eol=# delims=" %%i in ("jdk-path.txt") do if not defined JAVA_HOME_DIR set "JAVA_HOME_DIR=%%i"
set "JAVA_CMD="
if defined JAVA_HOME_DIR (
    set "JAVA_CMD=%JAVA_HOME_DIR%\bin\java.exe"
) else (
    for /f "delims=" %%i in ('where java.exe 2^>nul') do if not defined JAVA_CMD set "JAVA_CMD=%%i"
)
if not defined JAVA_CMD (
    echo [错误] 未检测到 Java 运行环境，请安装 JDK 21 及以上版本并把 java 加入 PATH。
    pause
    exit /b 1
)
rem 使用同一 JDK 的 javaw，避免运行期间保留控制台窗口
for %%i in ("%JAVA_CMD%") do set "JAVAW_CMD=%%~dpijavaw.exe"
if not exist "%JAVAW_CMD%" (
    echo [错误] 未找到无控制台启动程序：%JAVAW_CMD%
    pause
    exit /b 1
)

"%JAVA_CMD%" -version >nul 2>nul
if errorlevel 1 (
    echo [错误] Java 不可用：%JAVA_CMD%
    echo 请修正 jdk-path.txt，或安装 JDK 21 及以上版本并把 java 加入 PATH。
    pause
    exit /b 1
)
if not exist "pdms-import-tool.jar" (
    echo [错误] 当前目录下没有找到 pdms-import-tool.jar：%CD%
    pause
    exit /b 1
)

echo Java 路径：%JAVA_CMD%
"%JAVA_CMD%" -version 2>&1
echo.
echo 正在启动 PDMS 数据导入工具...
start "" "%JAVAW_CMD%" -Dfile.encoding=UTF-8 -Xms1g -Xmx2g -jar "%~dp0pdms-import-tool.jar"
if errorlevel 1 (
    echo [错误] 启动失败，请检查 Java 环境和程序文件。
    pause
    exit /b 1
)
exit /b 0
