@echo off
setlocal
cd /d "%~dp0"
echo Building Akhawain Ledger v7 (debug APK)...
if exist gradlew.bat goto usewrapper
where gradle >nul 2>nul
if errorlevel 1 goto nogradle
call gradle assembleDebug
goto done
:usewrapper
call gradlew.bat assembleDebug
goto done
:nogradle
echo.
echo Gradle was not found on PATH.
echo Easiest way: open this folder in Android Studio and use Build ^> Build APK(s).
echo (Requires JDK 17 and Gradle 8.9 or newer; Android Studio downloads them automatically.)
pause
exit /b 1
:done
if errorlevel 1 goto failed
echo.
echo APK created at: app\build\outputs\apk\debug\app-debug.apk
pause
exit /b 0
:failed
echo.
echo Build failed. Check the messages above, or build from Android Studio.
pause
exit /b 1
