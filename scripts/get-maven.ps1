param(
    [string]$Version = "3.9.9",
    [string]$Dest = ".maven",
    [string]$Sha512 = ""
)
$ErrorActionPreference = "Stop"

$url = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$Version/apache-maven-$Version-bin.tar.gz"
$tgz = Join-Path $env:TEMP "apache-maven-$Version-bin.tar.gz"

Write-Host "[get-maven] downloading $url"
Invoke-WebRequest -Uri $url -OutFile $tgz

if ($Sha512 -ne "") {
    $actual = (Get-FileHash -Algorithm SHA512 -Path $tgz).Hash.ToLower()
    if ($actual -ne $Sha512.ToLower()) {
        Remove-Item $tgz -Force
        throw "[get-maven] checksum mismatch: expected $Sha512 but got $actual"
    }
    Write-Host "[get-maven] checksum verified"
}

New-Item -ItemType Directory -Force -Path $Dest | Out-Null
Write-Host "[get-maven] extracting to $Dest"
# Windows 10/Server 2019+ ship bsdtar as tar.exe, which handles .tar.gz.
& tar -xzf $tgz -C $Dest
if ($LASTEXITCODE -ne 0) { throw "[get-maven] 'tar' failed (need Windows 10+ tar.exe). Install Maven manually, or use Docker." }
Remove-Item $tgz -Force

Write-Host "[get-maven] done: $Dest\apache-maven-$Version"
