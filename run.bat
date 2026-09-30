@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem ---------- 检查 java 是否可用 ----------
where java >nul 2>nul
if errorlevel 1 (
    echo [错误] 未检测到 Java 运行环境，请安装 JDK 21 及以上版本，并确保 java 已加入 PATH。
    echo.
    pause
    exit /b 1
)

rem ---------- 检查 JDK 版本（最低 21）----------
set "JVER="
for /f "tokens=3 delims= " %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined JVER set "JVER=%%~v"
)

set "JMAJOR="
for /f "tokens=1,2 delims=." %%a in ("%JVER%") do (
    set "JMAJOR=%%a"
    if "%%a"=="1" set "JMAJOR=%%b"
)

echo %JMAJOR%| findstr /r "^[0-9][0-9]*" >nul
if errorlevel 1 (
    echo [错误] 无法识别 Java 版本，请安装 JDK 21 及以上版本。
    echo.
    pause
    exit /b 1
)

if %JMAJOR% LSS 21 (
    echo [错误] 当前 Java 版本为 %JMAJOR%（%JVER%），低于最低要求。
    echo        请安装 JDK 21 及以上版本，并确保 PATH 中优先指向该版本。
    echo.
    pause
    exit /b 1
)

java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Xmx1g -jar pdms-import-tool.jar

if errorlevel 1 (
    echo.
    echo [错误] 启动失败，请查看以上提示信息。
    pause
)
