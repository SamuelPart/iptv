@echo off
setlocal
cd /d "%~dp0"

echo ==================================================
echo    IPTV - sincronizar, limpiar y compilar
echo ==================================================
echo.

echo [1/4] Sincronizando con GitHub (rama arena/01a06729-iptv)...
git fetch origin
git checkout arena/01a06729-iptv
git reset --hard origin/arena/01a06729-iptv
echo.

echo [2/4] Borrando caches de compilacion...
if exist "app\build" rmdir /s /q "app\build"
if exist ".gradle" rmdir /s /q ".gradle"
if exist "build" rmdir /s /q "build"
if exist ".idea\caches" rmdir /s /q ".idea\caches"
if exist ".kotlin" rmdir /s /q ".kotlin"
echo.

echo [3/4] Verificando que UiMotion.kt tenga springPress...
findstr /C:"fun View.springPress" "app\src\main\java\com\samuelpart\iptvplayer\UiMotion.kt" >nul
if errorlevel 1 (
    echo    [ERROR] UiMotion.kt no tiene springPress. La sincronizacion fallo.
) else (
    echo    [OK] UiMotion.kt tiene springPress.
)
echo.

echo [4/4] Compilando (puede tardar varios minutos)...
if exist "gradle\wrapper\gradle-wrapper.jar" (
    call gradlew.bat clean assembleDebug
    if errorlevel 1 (
        echo.
        echo    [ERROR] Fallo la compilacion. Copia el error y envialo.
    ) else (
        echo.
        echo    [OK] APK generado en app\build\outputs\apk\debug\
    )
) else (
    echo    No se encontro gradle-wrapper.jar. Abre Android Studio y dale:
    echo    Build -^> Clean Project  y luego  Run.
)
echo.
pause
