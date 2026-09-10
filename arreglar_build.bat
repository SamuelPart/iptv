@echo off
setlocal
cd /d "%~dp0"

echo ==================================================
echo    IPTV - sincronizar, limpiar y compilar
echo ==================================================
echo.

echo [1/5] Verificando Java 17...
if not defined JAVA_HOME (
    echo    [AVISO] JAVA_HOME no esta definido. Usa JDK 17 antes de compilar:
    echo           set JAVA_HOME=C:\Program Files\Java\jdk-17
    echo.
)

echo [2/5] Sincronizando con GitHub (rama arena/01a06729-iptv)...
git fetch origin
git checkout -f -B arena/01a06729-iptv origin/arena/01a06729-iptv
git reset --hard origin/arena/01a06729-iptv
echo    [OK] Repositorio sincronizado con el ultimo commit remoto.
echo.

echo [3/5] Borrando caches de compilacion...
if exist "app\build" rmdir /s /q "app\build"
if exist ".gradle" rmdir /s /q ".gradle"
if exist "build" rmdir /s /q "build"
if exist ".idea\caches" rmdir /s /q ".idea\caches"
if exist ".kotlin" rmdir /s /q ".kotlin"
echo.

echo [4/5] Verificando que la sincronizacion quedo completa...
set "FAIL=0"
findstr /C:"fun View.springPress" "app\src\main\java\com\samuelpart\iptvplayer\UiMotion.kt" >nul
if errorlevel 1 ( echo    [ERROR] UiMotion.kt no tiene springPress. & set "FAIL=1" ) else ( echo    [OK] UiMotion.kt tiene springPress. )
findstr /C:"GRID_AD_ROWS" "app\src\main\java\com\samuelpart\iptvplayer\NativeAds.kt" >nul
if errorlevel 1 ( echo    [ERROR] NativeAds.kt no tiene GRID_AD_ROWS. Sincronizacion incompleta. & set "FAIL=1" ) else ( echo    [OK] NativeAds.kt tiene GRID_AD_ROWS. )
findstr /C:"gridColumns" "app\src\main\java\com\samuelpart\iptvplayer\ChannelAdapter.kt" >nul
if errorlevel 1 ( echo    [ERROR] ChannelAdapter.kt no tiene gridColumns. Sincronizacion incompleta. & set "FAIL=1" ) else ( echo    [OK] ChannelAdapter.kt tiene gridColumns. )
findstr /C:"fun refreshCatalog" "app\src\main\java\com\samuelpart\iptvplayer\CineRepository.kt" >nul
if errorlevel 1 ( echo    [ERROR] CineRepository.kt no tiene refreshCatalog. Sincronizacion incompleta. & set "FAIL=1" ) else ( echo    [OK] CineRepository.kt tiene refreshCatalog. )
if not exist "app\src\main\java\com\samuelpart\iptvplayer\CatalogBot.kt" (
    echo    [ERROR] No existe CatalogBot.kt. Sincronizacion incompleta.
    set "FAIL=1"
) else (
    echo    [OK] CatalogBot.kt existe.
)
if "%FAIL%"=="1" (
    echo.
    echo    La sincronizacion quedo INCOMPLETA. Borra la carpeta y vuelve a clonar:
    echo       cd ..
    echo       git clone https://github.com/SamuelPart/iptv.git
    echo       cd iptv
    echo       git checkout arena/01a06729-iptv
    echo.
)
echo.

echo [5/5] Compilando (puede tardar varios minutos)...
if exist "gradle\wrapper\gradle-wrapper.jar" (
    call gradlew.bat clean assembleDebug
    if errorlevel 1 (
        echo.
        echo    [ERROR] Fallo la compilacion. Copia el error y envialo.
    ) else (
        echo.
        echo    [OK] APK generado en app\build\outputs\apk\debug\
        echo         Instalar: adb install -r app\build\outputs\apk\debug\app-debug.apk
    )
) else (
    echo    No se encontro gradle-wrapper.jar. Abre Android Studio y dale:
    echo    Build -^> Clean Project  y luego  Run.
)
echo.
pause
