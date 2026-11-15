@echo off
chcp 65001 >nul
cd /d "%~dp0"
where python >nul 2>nul || (echo Python est introuvable. Installez-le depuis https://www.python.org/downloads/ en cochant "Add python.exe to PATH". & pause & exit /b 1)
if not exist .venv (
  echo Premiere utilisation : installation en cours...
  python -m venv .venv || (pause & exit /b 1)
  .venv\Scripts\python -m pip install -q -r requirements.txt || (pause & exit /b 1)
)
.venv\Scripts\python scanner.py %*
pause
