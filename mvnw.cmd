@echo off
rem Lightweight Maven wrapper for imini (Windows). POSIX companion: mvnw
setlocal
set "BASEDIR=%~dp0"
set "VER=3.9.9"
set "SHA="
for /f "tokens=2 delims==" %%v in ('findstr /b "distributionVersion=" "%BASEDIR%.mvn\wrapper\maven-wrapper.properties" 2^>nul') do set "VER=%%v"
for /f "tokens=2 delims==" %%s in ('findstr /b "distributionSha512Sum=" "%BASEDIR%.mvn\wrapper\maven-wrapper.properties" 2^>nul') do set "SHA=%%s"

rem 1) Use a system Maven if present.
where mvn >nul 2>nul
if not errorlevel 1 (
  call mvn %*
  exit /b %errorlevel%
)

rem 2) Otherwise use (or fetch, via scripts\get-maven.ps1) a wrapper-managed Maven under .maven\.
set "MVN_BIN=%BASEDIR%.maven\apache-maven-%VER%\bin\mvn.cmd"
if not exist "%MVN_BIN%" (
  echo [mvnw] no system Maven found; downloading Apache Maven %VER% ^(one time^)...
  powershell -NoProfile -ExecutionPolicy Bypass -File "%BASEDIR%scripts\get-maven.ps1" -Version %VER% -Dest "%BASEDIR%.maven" -Sha512 "%SHA%"
  if errorlevel 1 exit /b 1
)
call "%MVN_BIN%" %*
exit /b %errorlevel%
