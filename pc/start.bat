@echo off
chcp 65001 >nul
title 互传助手 LinkAssist
cd /d %~dp0
python -c "import aiohttp" 2>nul || python -m pip install aiohttp
python server.py
pause
