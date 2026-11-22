@echo off
setlocal
chcp 65001 >nul
echo Подпись Private Diary на вашем компьютере
echo Пароль вводится только в скрытом запросе PowerShell.
echo.
if not exist "%~dp0sign-release.ps1" (
  echo ОШИБКА: рядом с этим файлом нет sign-release.ps1. Распакуйте весь архив.
  pause
  exit /b 1
)
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0sign-release.ps1"
set "SIGN_EXIT=%ERRORLEVEL%"
echo.
if not "%SIGN_EXIT%"=="0" echo Подпись не завершена. Прочитайте сообщение выше.
echo Нажмите любую клавишу, чтобы закрыть окно.
pause >nul
exit /b %SIGN_EXIT%
