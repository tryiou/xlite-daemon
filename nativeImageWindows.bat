@echo off
setlocal enabledelayedexpansion

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
set "GRAALVM_JDK_DIR="

REM Find the GraalVM JDK directory dynamically (wildcard for version)
echo   - Searching for existing GraalVM installation...
set "GRAALVM_NOT_FOUND=1"
for /d %%D in ("%GRAALVM_BASE%\graalvm-community-openjdk-21*") do (
    echo     Found: %%~nxD
    set "GRAALVM_JDK_DIR=%%~nxD"
    set "GRAALVM_NOT_FOUND=0"
    goto :found_graalvm_dir
)

:found_graalvm_dir

REM Check if wildcard found a match
if "%GRAALVM_NOT_FOUND%"=="1" (
    echo Error: No GraalVM JDK 21.x directory found in %GRAALVM_BASE%
    REM Download and install GraalVM JDK 21 directly
    echo Downloading GraalVM JDK 21...
    
    REM Check if graalvm.zip already exists to avoid re-downloading
    if exist "graalvm.zip" (
        echo GraalVM zip file already exists, skipping download.
    ) else (
        curl -L -o graalvm.zip "https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_windows-x64_bin.zip" --location --retry 3 --fail --show-error

        if %ERRORLEVEL% NEQ 0 (
            echo Failed to download GraalVM. Please check your internet connection.
            exit /b %ERRORLEVEL%
        )
    )

    echo Extracting GraalVM...
    echo   - Removing old GraalVM directory...
    rmdir /s /q "C:\Program Files\GraalVM" 2>nul
    echo   - Creating GraalVM directory...
    mkdir "C:\Program Files\GraalVM"
    echo   - Extracting archive to C:\Program Files\GraalVM...
    powershell -Command "Expand-Archive -Path graalvm.zip -DestinationPath 'C:\Program Files\GraalVM' -Force"

    if %ERRORLEVEL% NEQ 0 (
        echo Failed to extract GraalVM archive.
        exit /b %ERRORLEVEL%
    )

    REM Find the GraalVM JDK directory dynamically (wildcard for version) immediately after extraction
    echo   - Searching for extracted GraalVM directory...
    set "GRAALVM_JDK_DIR="
    for /d %%D in ("%GRAALVM_BASE%\graalvm-community-openjdk-21*") do (
        echo     Found: %%~nxD
        set "GRAALVM_JDK_DIR=%%~nxD"
        goto :found_extracted_dir
    )

    :found_extracted_dir

    if "%GRAALVM_JDK_DIR%"=="" (
        echo Failed to find extracted GraalVM directory
        echo   - Available directories in %GRAALVM_BASE%:
        dir "%GRAALVM_BASE%" /b /ad
        exit /b 1
    )

    set "GRAALVM_PATH=%GRAALVM_BASE%\%GRAALVM_JDK_DIR%"

    REM Verify extraction succeeded
    if not exist "%GRAALVM_PATH%" (
        echo Failed to extract GraalVM to %GRAALVM_PATH%
        exit /b 1
    )

    set "GRAALVM_NOT_FOUND=0"
)

set "GRAALVM_PATH=%GRAALVM_BASE%\%GRAALVM_JDK_DIR%"
set "GRAALVM_BIN=%GRAALVM_PATH%\bin"

echo   - Checking GraalVM JDK directory...
if exist "%GRAALVM_PATH%" (
    echo     ✓ GraalVM JDK directory exists at: %GRAALVM_PATH%
    echo     ✓ Using wildcard-matched directory: %GRAALVM_JDK_DIR%
    dir "%GRAALVM_PATH%" /b
) else (
    echo     ✗ GraalVM JDK directory NOT found at: %GRAALVM_PATH%
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

echo Building XLite Daemon with nativeCompile...

REM Build the project using Gradle nativeCompile task
gradlew.bat clean nativeCompile --info

REM Additional check for build output
if not exist build\native\nativeCompile\xlite-daemon.exe (
    echo Build completed but native image not found at expected location
    exit /b 1
)

if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    exit /b %ERRORLEVEL%
)

echo Build completed successfully!

REM Rename the output file
if exist build\native\nativeCompile\xlite-daemon.exe (
    ren build\native\nativeCompile\xlite-daemon.exe xlite-daemon-win64.exe
    echo Native image created: build\native\nativeCompile\xlite-daemon-win64.exe
) else (
    echo Error: Native image not found at expected location
    exit /b 1
)

echo Native compilation complete!
