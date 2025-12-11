@echo off

REM Check if Chocolatey is installed, if not install it
where /q choco
if %ERRORLEVEL% NEQ 0 (
    echo Chocolatey not found. Installing Chocolatey...
    powershell -Command "Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072; iex ((New-Object System.Net.WebClient).DownloadString('https://chocolatey.org/install.ps1'))"
)

REM Install Needed packages
choco install windows-sdk-10.0 -y
choco install -y visualstudio2022-workload-vctools

REM Define GraalVM installation path
set "GRAALVM_BASE=C:\Program Files\GraalVM"

REM Find the GraalVM JDK directory dynamically (wildcard for version)
echo   - Searching for existing GraalVM installation...

REM Check if GraalVM directory exists
if not exist "%GRAALVM_BASE%" mkdir "%GRAALVM_BASE%"

REM Look for existing GraalVM installation
set "GRAALVM_FOUND=0"
for /d %%D in ("%GRAALVM_BASE%\graalvm-community-openjdk-21*") do (
    echo     Found existing installation: %%~nxD
    set "GRAALVM_JDK_DIR=%%~nxD"
    set "GRAALVM_FOUND=1"
    goto :found_graalvm
)

:not_found
echo Error: No GraalVM JDK 21.x directory found in %GRAALVM_BASE%
REM Download and install GraalVM JDK 21 directly
echo Downloading GraalVM JDK 21...

REM Check if graalvm.zip already exists to avoid re-downloading
if exist "graalvm.zip" (
    echo GraalVM zip file already exists, skipping download.
) else (
    curl -L --location --retry 3 -o graalvm.zip "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_windows-x64_bin.zip"
    
    if not exist graalvm.zip (
        echo Failed to download GraalVM. Please check your internet connection.
        exit /b 1
    )
    echo Download successful.
)

echo Extracting GraalVM...
echo   - Removing old GraalVM directory...
rmdir /s /q "%GRAALVM_BASE%" 2>nul
echo   - Creating GraalVM directory...
mkdir "%GRAALVM_BASE%"
echo   - Checking if graalvm.zip exists and is readable...
if not exist "graalvm.zip" (
    echo Error: graalvm.zip file not found!
    exit /b 1
)
echo   - File size:
for %%F in (graalvm.zip) do echo     %%~zF bytes
echo   - Extracting archive to %GRAALVM_BASE%...
powershell -Command "Expand-Archive -Path graalvm.zip -DestinationPath '%GRAALVM_BASE%' -Force"

echo   - Verifying extraction...
timeout /t 2 /nobreak >nul

REM Find the extracted directory
set "GRAALVM_FOUND=0"
for /d %%D in ("%GRAALVM_BASE%\graalvm-community-openjdk-21*") do (
    echo     Found extracted directory: %%~nxD
    set "GRAALVM_JDK_DIR=%%~nxD"
    set "GRAALVM_FOUND=1"
    goto :found_graalvm
)

echo Failed to extract GraalVM - no directory found after extraction
echo   - Available directories in %GRAALVM_BASE%:
dir "%GRAALVM_BASE%" /b /ad
exit /b 1

:found_graalvm
set "GRAALVM_PATH=%GRAALVM_BASE%\%GRAALVM_JDK_DIR%"
set "GRAALVM_BIN=%GRAALVM_PATH%\bin"

echo   - Checking GraalVM JDK directory...
if exist "%GRAALVM_PATH%" (
    echo     GraalVM JDK directory exists at: %GRAALVM_PATH%
    echo     Using directory: %GRAALVM_JDK_DIR%
) else (
    echo     GraalVM JDK directory NOT found at: %GRAALVM_PATH%
    exit /b 1
)

echo Setting environment variables...
setx JAVA_HOME "%GRAALVM_PATH%"
setx PATH "%GRAALVM_BIN%;%PATH%"

REM Set environment variables for current session
set "JAVA_HOME=%GRAALVM_PATH%"
set "PATH=%GRAALVM_BIN%;%PATH%"

REM Load the Build Tools environment
if not exist "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" (
    echo Visual Studio 2022 Build Tools not found at expected location.
    exit /b 1
)

call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

if %ERRORLEVEL% NEQ 0 (
    echo Failed to load Visual Studio Build Tools environment.
    exit /b %ERRORLEVEL%
)

REM Set environment variables for Windows SDK 10
set "SDK_VERSION="
for /f "delims=" %%a in ('dir /b /ad /o-n "%ProgramFiles(x86)%\Windows Kits\10\bin\*" 2^>nul') do (
    SET "SDK_VERSION=%%a"
    goto :found_sdk
)

:found_sdk
if "%SDK_VERSION%"=="" (
    echo Windows SDK not found
    exit /b 1
)

SET "SDK_BIN_PATH=%ProgramFiles(x86)%\Windows Kits\10\bin\%SDK_VERSION%\x64"
SET "SDK_INCLUDE_PATH=%ProgramFiles(x86)%\Windows Kits\10\include\%SDK_VERSION%\shared;%ProgramFiles(x86)%\Windows Kits\10\include\%SDK_VERSION%\um;%ProgramFiles(x86)%\Windows Kits\10\include\%SDK_VERSION%\ucrt"
SET "SDK_LIB_PATH=%ProgramFiles(x86)%\Windows Kits\10\lib\%SDK_VERSION%\um\x64;%LIB%"

REM Update environment variables for current session
SET "PATH=%SDK_BIN_PATH%;%PATH%"
SET "INCLUDE=%SDK_INCLUDE_PATH%;%INCLUDE%"
SET "LIB=%SDK_LIB_PATH%;%LIB%"

echo Building XLite Daemon with Maven native profile...

REM Check if Maven wrapper exists, if not use mvn directly
if exist mvnw.cmd (
    echo Using Maven wrapper (mvnw.cmd)...
    set "MAVEN_CMD=mvnw.cmd"
) else if exist mvnw.bat (
    echo Using Maven wrapper (mvnw.bat)...
    set "MAVEN_CMD=mvnw.bat"
) else (
    echo Maven wrapper not found, using system mvn command...
    set "MAVEN_CMD=mvn"
)

REM Build the project using Maven with native profile
echo Running: %MAVEN_CMD% clean compile exec:java -Pnative -DskipTests
%MAVEN_CMD% clean compile exec:java -Pnative -DskipTests

if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)

REM Additional check for build output
if not exist target\xlite-daemon.exe (
    echo Build completed but native image not found at expected location
    exit /b 1
)

echo Build completed successfully!

REM Rename the output file
if exist target\xlite-daemon.exe (
    ren target\xlite-daemon.exe xlite-daemon-win64.exe
    echo Native image created: target\xlite-daemon-win64.exe
) else (
    echo Error: Native image not found at expected location
    exit /b 1
)

echo Native compilation complete!