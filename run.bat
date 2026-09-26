@echo off
title Ano-Link PC Controller
cd /d "%~dp0pc-controller"

where node >nul 2>nul
if %errorlevel% neq 0 (
    echo =======================================================
    echo [ERROR] Node.js is not installed!
    echo Please download and install Node.js (LTS) from:
    echo https://nodejs.org
    echo =======================================================
    pause
    exit /b
)

if not exist node_modules (
    echo =======================================================
    echo [Ano-Link] Installing required dependencies...
    echo =======================================================
    call npm install
)

start http://localhost:8080
call npm start
