@echo off
setlocal
if "%~1"=="" (
  echo Usage: prompt.bat "your prompt"
  exit /b 1
)
set "GOALMAKER_PROMPT=%~1"
powershell -NoProfile -Command "$body = @{ prompt = $env:GOALMAKER_PROMPT } | ConvertTo-Json -Compress; try { (Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/prompt' -ContentType 'application/json' -Body $body).response } catch { Write-Error $_; exit 1 }"
endlocal
