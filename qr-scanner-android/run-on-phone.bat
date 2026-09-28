@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo.
echo === QR Scan — install and run on phone ===
echo.

if exist "D:\android-build\sdk" (
  set "SDK_DIR=D:\android-build\sdk"
  set "SDK_DIR_PROP=D:\\android-build\\sdk"
) else if exist "%LOCALAPPDATA%\Android\Sdk" (
  set "SDK_DIR=%LOCALAPPDATA%\Android\Sdk"
  set "SDK_DIR_PROP=%LOCALAPPDATA:\=\\%\\Android\\Sdk"
) else (
  echo Android SDK not found.
  echo Expected D:\android-build\sdk or %%LOCALAPPDATA%%\Android\Sdk
  exit /b 1
)

if exist "D:\android-build\jdk\jdk-17.0.20.1+1" (
  set "JAVA_HOME=D:\android-build\jdk\jdk-17.0.20.1+1"
)
if exist "D:\android-build\gradle-home" (
  set "GRADLE_USER_HOME=D:\android-build\gradle-home"
)

set "ADB=%SDK_DIR%\platform-tools\adb.exe"
if not exist "%ADB%" (
  echo adb not found at %ADB%
  exit /b 1
)

if not exist "local.properties" (
  echo sdk.dir=%SDK_DIR_PROP%> local.properties
)

echo Waiting for a phone...
"%ADB%" start-server >nul
"%ADB%" devices
"%ADB%" get-state 1>nul 2>nul
if errorlevel 1 (
  echo.
  echo No phone found. Plug it in over USB, then on the phone:
  echo   1^) Settings -^> About phone -^> tap Build number 7 times
  echo   2^) Settings -^> Developer options -^> USB debugging ON
  echo   3^) Unlock the phone and tap Allow when it asks
  echo   4^) Run this again
  echo.
  echo Wireless later:  adb tcpip 5555   then   adb connect PHONE-IP:5555
  echo.
  exit /b 1
)

echo Building and installing...
call gradlew.bat :app:installDebug
if errorlevel 1 (
  echo Install failed.
  exit /b 1
)

echo Opening QR Scan...
"%ADB%" shell am start -n com.sumo.qrscanner/.MainActivity
echo.
echo Phone is running the debug build. Change code, then run this again.
echo There is no Flutter-style hot reload — each run rebuilds only what changed.
echo.
endlocal
