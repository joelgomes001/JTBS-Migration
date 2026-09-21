@echo off
set IP=192.168.0.187
set ADB="C:\Users\JTBS-LIVE\AppData\Local\Android\Sdk\platform-tools\adb.exe"

echo Waiting for %IP% to come online...

:loop
ping -n 1 -w 500 %IP% | find "TTL=" >nul
if errorlevel 1 (
    goto loop
)

echo %IP% is online! Connecting ADB...
%ADB% connect %IP%:5555
timeout /t 1 /nobreak >nul
echo Sending adb reboot recovery...
%ADB% -s %IP%:5555 reboot recovery
echo Done.
pause
