@echo off
cd /d "%~dp0"
if not exist "native\windows-x86_64\hammer.dll" (
    echo Build the native library first. See README.md for Windows build steps.
    pause
    exit /b 1
)
java -Djava.library.path=native/windows-x86_64 -jar hammer-server.jar %*
pause
