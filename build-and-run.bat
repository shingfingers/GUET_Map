@echo off
chcp 65001 >nul
echo ========================================
echo GUET_Map 构建和运行脚本
echo ========================================
echo.

cd /d "%~dp0"

echo [1/3] 检查 Gradle...
call gradlew --version >nul 2>&1
if %errorlevel% neq 0 (
    echo 错误: Gradle 不可用
    pause
    exit /b 1
)
echo ✓ Gradle 正常
echo.

echo [2/3] 构建应用...
call gradlew assembleDebug
if %errorlevel% neq 0 (
    echo 错误: 构建失败
    pause
    exit /b 1
)
echo.
echo ✓ 构建成功
echo.

echo [3/3] 检查 APK 文件...
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo ✓ APK 文件已生成
    echo.
    echo APK 位置: app\build\outputs\apk\debug\app-debug.apk
    echo.
    echo 你可以：
    echo 1. 使用 Android Studio 安装到模拟器
    echo 2. 使用 adb 安装到真机: adb install app\build\outputs\apk\debug\app-debug.apk
    echo 3. 在 Android Studio 中直接运行项目
) else (
    echo 错误: APK 文件未找到
)

echo.
echo ========================================
echo 完成！
echo ========================================
pause
