@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul || (
  echo Java 17 or newer is required.
  exit /b 1
)
where llama-server.exe >nul 2>nul
if errorlevel 1 if not exist "%~dp0llama-server.exe" echo WARNING: llama-server.exe was not found on PATH or in this folder.
if exist "%~dp0mvnw.cmd" (
  call "%~dp0mvnw.cmd" spring-boot:run
) else (
  call mvn spring-boot:run
)
endlocal
