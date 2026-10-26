@echo off
cd /d "E:\vip\TACZ-Legacy"

echo === Building (build -x test) ===
gradlew build -x test
if errorlevel 1 goto fail

if not exist releases mkdir releases

echo === Archiving jars to releases\ ===
for %%f in (build\libs\*.jar) do (
    powershell -NoProfile -Command "Copy-Item -LiteralPath '%%f' -Destination ('releases\' + [System.IO.Path]::GetFileNameWithoutExtension('%%f') + '_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '.jar') -Force"
)
echo.
echo Done. Archived jars are safe from 'gradlew clean' (releases is outside build).
goto end

:fail
echo BUILD FAILED - nothing was archived.
:end
pause
