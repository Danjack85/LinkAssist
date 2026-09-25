@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
python -c "import aiohttp, qrcode, webview, PyInstaller, pystray, PIL" 2>nul
if errorlevel 1 (
  echo Missing build dependencies. Run: python -m pip install -r requirements-dev.txt
  exit /b 1
)
python -m PyInstaller --noconfirm --clean --distpath "..\artifacts\windows" --workpath "..\artifacts\pyinstaller" LinkAssist.spec
if errorlevel 1 exit /b 1
echo Build succeeded: ..\artifacts\windows\LinkAssist.exe
