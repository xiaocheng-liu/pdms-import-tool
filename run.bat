@echo off
chcp 65001 >nul
setlocal

rem ============================================================
rem  PDMS 数据导入工具 - Windows 启动脚本
rem
rem  用法：
rem    run.bat                                      自动查找 JDK
rem    run.bat "C:\Program Files\Java\jdk-17"        指定 JDK 安装目录
rem    run.bat --jdk "C:\Program Files\Java\jdk-17"  同上
rem
rem  JDK 查找优先级：
rem    命令行参数 > 环境变量 PDMS_JAVA_HOME > 同目录 jdk-path.txt > JAVA_HOME > PATH 中的 java
rem  前三种为"显式指定"，路径无效时直接报错退出，不会悄悄改用其它版本。
rem
rem  要求：JDK 11 / 17 / 21
rem ============================================================

set "APP_DIR=%~dp0"

rem ---------- 解析命令行参数 ----------
set "JDK_ARG="
if /i "%~1"=="--jdk" (
    set "JDK_ARG=%~2"
) else if /i "%~1"=="-j" (
    set "JDK_ARG=%~2"
) else if not "%~1"=="" (
    if /i "%~1"=="--help" goto :usage
    if /i "%~1"=="-h" goto :usage
    set "JDK_ARG=%~1"
)

rem ---------- 定位 jar ----------
set "JAR_PATH=%APP_DIR%pdms-import-tool.jar"
if not exist "%JAR_PATH%" set "JAR_PATH=%APP_DIR%target\pdms-import-tool.jar"

if not exist "%JAR_PATH%" (
    if exist "%APP_DIR%pom.xml" (
        echo [提示] 未找到 jar，尝试自动打包...
        where mvn >nul 2>nul
        if errorlevel 1 (
            echo [错误] 当前目录没有 jar，且未安装 Maven。请使用发布包中的完整文件。
            goto :fail
        )
        pushd "%APP_DIR%"
        call mvn -o -q package -DskipTests
        popd
        set "JAR_PATH=%APP_DIR%target\pdms-import-tool.jar"
    )
)

if not exist "%JAR_PATH%" (
    echo [错误] 未找到 pdms-import-tool.jar，请确认解压完整：%APP_DIR%
    goto :fail
)

rem ---------- 读取 jdk-path.txt（忽略空行与 # 开头的注释行）----------
set "JDK_FROM_FILE="
if exist "%APP_DIR%jdk-path.txt" (
    for /f "usebackq eol=# tokens=* delims=" %%a in ("%APP_DIR%jdk-path.txt") do (
        if not defined JDK_FROM_FILE set "JDK_FROM_FILE=%%~a"
    )
)

rem ---------- 定位 java ----------
set "JAVA_CMD="

if defined JDK_ARG (
    call :useJdk "命令行参数" "%JDK_ARG%"
    if errorlevel 1 goto :fail
)

if not defined JAVA_CMD if defined PDMS_JAVA_HOME (
    call :useJdk "环境变量 PDMS_JAVA_HOME" "%PDMS_JAVA_HOME%"
    if errorlevel 1 goto :fail
)

if not defined JAVA_CMD if defined JDK_FROM_FILE (
    call :useJdk "jdk-path.txt" "%JDK_FROM_FILE%"
    if errorlevel 1 goto :fail
)

if not defined JAVA_CMD if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
        echo [信息] 使用 JDK（环境变量 JAVA_HOME）：%JAVA_HOME%
    )
)

if not defined JAVA_CMD (
    where java >nul 2>nul
    if errorlevel 1 (
        echo [错误] 未检测到 Java 运行环境，请安装 JDK 11 及以上版本
        echo        也可在本目录的 jdk-path.txt 中填写 JDK 安装目录，或用 run.bat ^<JDK目录^> 指定
        goto :fail
    )
    set "JAVA_CMD=java"
    echo [信息] 使用 PATH 中的 java
)

echo [信息] 正在启动 PDMS 数据导入工具...
start "PDMS 数据导入工具" "%JAVA_CMD%" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Xmx1g -jar "%JAR_PATH%"
exit /b 0

:usage
echo 用法：run.bat [JDK安装目录]  或  run.bat --jdk ^<JDK安装目录^>
echo 也可把 JDK 安装目录写进 %APP_DIR%jdk-path.txt
exit /b 0

:useJdk
rem %1 = 来源说明，%2 = JDK 安装目录
set "_JDK_DIR=%~2"
if not exist "%_JDK_DIR%\bin\java.exe" (
    echo [错误] %~1 指定的 JDK 路径无效：%_JDK_DIR%
    echo        未找到 %_JDK_DIR%\bin\java.exe，该路径应指向 JDK 安装目录
    exit /b 1
)
set "JAVA_CMD=%_JDK_DIR%\bin\java.exe"
echo [信息] 使用 JDK（%~1）：%_JDK_DIR%
exit /b 0

:fail
echo.
echo 启动失败，请查看以上提示信息。
pause
exit /b 1
