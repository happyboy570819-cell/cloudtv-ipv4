@echo off
chcp 65001 >nul
title 云视 TV (CloudTV-IPv4) - 本地 APK 编译助手
echo ============================================================================
echo   云视 TV (纯 IPv4 防卡顿电视盒子版) - 本地/云端 APK 打包指南
echo ============================================================================
echo.
if "%JAVA_HOME%"=="" (
    echo [提示] 检测到当前电脑尚未安装 JDK 17 或 Android SDK。
    echo.
    echo 您有两种最省心的方式获取编译好的 APK 安装包：
    echo   方式 A ^(推荐，免装几十G开发环境^)：
    echo     将本工程上传至 GitHub 仓库，工程已内置 .github\workflows\build-apk.yml，
    echo     GitHub Actions 会自动在云端编译并输出签名好的 Release APK！
    echo.
    echo   方式 B ^(本机使用 winget 一键安装编译环境^)：
    echo     1. 在 PowerShell 运行: winget install EclipseAdoptium.Temurin.17.JDK
    echo     2. 安装 Android Studio 或 Command-line tools 并设置 ANDROID_HOME
    echo     3. 重新双击本脚本即可自动运行 gradle assembleRelease 生成 APK。
    echo.
    pause
    exit /b 0
)

if exist "gradlew.bat" (
    call gradlew.bat assembleRelease
) else (
    gradle assembleRelease
)
echo.
echo 编译完成！APK 位于: app\build\outputs\apk\release\app-release.apk
pause
