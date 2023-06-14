@echo off

REM INSTALL CHOCO ?
REM @"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -InputFormat None -ExecutionPolicy Bypass -Command "[System.Net.ServicePointManager]::SecurityProtocol = 3072; iex ((New-Object System.Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))" && SET "PATH=%PATH%;%ALLUSERSPROFILE%\chocolatey\bin"

REM Install Needed packages
choco install windows-sdk-10 -y
REM # ? choco install visualstudio2019buildtools -y --package-parameters "--includeRecommended --includeOptional"
choco install -y visualstudio2019-workload-vctools
choco install openjdk --version 17 -y

call refreshenv

REM Build the project using Gradle
call gradlew.bat clean build
call gradlew.bat downloadGraalTooling
call gradlew.bat extractGraalTooling 

SET GRAAL_HOME=%USERPROFILE%\.gradle\caches\com.palantir.graal\22.3.0\17\graalvm-ce-java17-22.3.0
SET NATIVE_IMAGE=%GRAAL_HOME%\bin\native-image
SET CC_PACKAGED_JAR=%cd%\build\libs\xlite-daemon-0.5.12-all.jar

REM Load the Build Tools environment
call "C:\Program Files (x86)\Microsoft Visual Studio\2019\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

REM Set environment variables for Windows SDK 10
for /f "delims=" %%a in ('dir /b /ad /o-n "%ProgramFiles(x86)%\Windows Kits\10\bin\*"') do (
    SET "SDK_VERSION=%%a"
    goto :next
)

:next
SET "SDK_BIN_PATH=%ProgramFiles(x86)%\Windows Kits\10\bin\%SDK_VERSION%\x64"
SET "SDK_INCLUDE_PATH=%ProgramFiles(x86)%\Windows Kits\10\include\%SDK_VERSION%\shared;%ProgramFiles(x86)%\Windows Kits\10\include\%SDK_VERSION%\um;%ProgramFiles(x86)%\Windows Kits\10\include\%SDK_VERSION%\ucrt"
SET "SDK_LIB_PATH=%ProgramFiles(x86)%\Windows Kits\10\lib\%SDK_VERSION%\um\x64;%LIB%"

REM Update environment variables
SET "PATH=%SDK_BIN_PATH%;%PATH%"
SET "INCLUDE=%SDK_INCLUDE_PATH%;%INCLUDE%"
SET "LIB=%SDK_LIB_PATH%;%LIB%"

echo GRAAL_HOME=%GRAAL_HOME%
echo NATIVE_IMAGE=%NATIVE_IMAGE%
echo CC_PACKAGED_JAR=%CC_PACKAGED_JAR%

call %NATIVE_IMAGE% -jar %CC_PACKAGED_JAR% ^
    -H:Name=Cloudchains-SPV ^
    -H:Class=io.cloudchains.app.App ^
    -H:+JNI ^
    -H:+UseServiceLoaderFeature ^
    -H:-UseServiceLoaderFeature ^
    -H:ReflectionConfigurationFiles=contrib/netty-reflection.json ^
    -H:ResourceConfigurationFiles=contrib/resource-config.json ^
    -H:IncludeResources='.*/wordlist/english.txt$' ^
    -H:Log=registerResource ^
    --no-fallback ^
    --no-server ^
    -da ^
    --enable-url-protocols=http,https ^
    --initialize-at-build-time=io.netty ^
    --initialize-at-build-time=com.google.common ^
    --initialize-at-build-time=org.apache.commons.logging ^
    --initialize-at-build-time=org.slf4j.LoggerFactory ^
    --initialize-at-build-time=org.slf4j.impl.StaticLoggerBinder ^
    --initialize-at-build-time=org.slf4j.helpers.NOPLogger ^
    --initialize-at-build-time=org.slf4j.helpers.NOPLoggerFactory ^
    --initialize-at-build-time=org.slf4j.helpers.SubstituteLoggerFactory ^
    --initialize-at-build-time=org.slf4j.helpers.Util ^
    --initialize-at-build-time=org.bitcoinj.core.Utils ^
    --initialize-at-build-time=org.bitcoinj.core.Sha256Hash ^
    --initialize-at-build-time=org.bitcoinj.crypto.MnemonicCode ^
    --initialize-at-run-time=io.netty.util.internal.logging.Log4JLogger ^
    --initialize-at-run-time=io.netty.handler.codec.http.HttpObjectEncoder ^
    --initialize-at-run-time=io.netty.handler.codec.http2.DefaultHttp2FrameWriter ^
    --initialize-at-run-time=io.netty.handler.codec.http2.Http2CodecUtil ^
    --initialize-at-run-time=io.netty.buffer.PooledByteBufAllocator ^
    --initialize-at-run-time=io.netty.buffer.ByteBufAllocator ^
    --initialize-at-run-time=io.netty.buffer.ByteBufUtil ^
    --initialize-at-run-time=io.netty.buffer.AbstractReferenceCountedByteBuf ^
    --initialize-at-run-time=io.netty.handler.codec.http2.Http2CodecUtil ^
    --initialize-at-run-time=io.netty.handler.codec.http2.Http2ClientUpgradeCodec ^
    --initialize-at-run-time=io.netty.handler.codec.http2.Http2ConnectionHandler ^
    --initialize-at-run-time=io.netty.handler.codec.http2.DefaultHttp2FrameWriter ^
    --initialize-at-run-time=io.netty.util.AbstractReferenceCounted ^
    --initialize-at-run-time=io.netty.handler.codec.http.HttpObjectEncoder ^
    --initialize-at-run-time=io.netty.handler.codec.http.websocketx.WebSocket00FrameEncoder ^
    --initialize-at-run-time=io.netty.handler.codec.http.websocketx.extensions.compression.DeflateDecoder ^
    --initialize-at-run-time=io.netty.handler.ssl.util.ThreadLocalInsecureRandom ^
    --allow-incomplete-classpath ^
    --verbose ^
    -H:+ReportExceptionStackTraces
