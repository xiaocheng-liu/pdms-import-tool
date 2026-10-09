@echo off
rem 出错时也不关闭窗口：必要时自动重开一个常驻窗口再执行本脚本
if "%PDMS_BAT_CHILD%"=="" (set PDMS_BAT_CHILD=1 & cmd /k ""%~f0"" & exit /b)

rem 切到 UTF-8 代码页，保证中文提示与程序日志不乱码
chcp 65001 >nul
cd /d "%~dp0"
title PDMS 数据导入工具

rem 可选：把 JDK 安装目录写进 jdk-path.txt（一行，# 开头为注释）；不配则使用 PATH 里的 java
set "JAVA_HOME_DIR="
if exist "jdk-path.txt" for /f "usebackq eol=# delims=" %%i in ("jdk-path.txt") do if not defined JAVA_HOME_DIR set "JAVA_HOME_DIR=%%i"
if defined JAVA_HOME_DIR (set "JAVA_CMD=%JAVA_HOME_DIR%\bin\java.exe") else (set "JAVA_CMD=java")

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
"%JAVA_CMD%" -Dfile.encoding=UTF-8 -Xms1g -Xmx2g -jar pdms-import-tool.jar
echo.
echo 程序已退出，退出码：%ERRORLEVEL%
pause
