REM GRAALVM 20.1.0 IS REQUIRED - INSTALL FROM
REM https://github.com/graalvm/graalvm-ce-dev-builds/releases/download/20.1.0-dev-20200212_0349/graalvm-ce-java8-windows-amd64-20.1.0-dev.zip

choco install -y windows-sdk-7.1 kb2519277
choco install -y vcredist2010


SET NATIVE_IMAGE=%1
SET CC_PACKAGED_JAR=%2
SET CC_DIR=%3

echo Initializing Microsoft SDK 7.1 environment
call "C:\Program Files\Microsoft SDKs\Windows\v7.1\Bin\SetEnv.cmd"

call %NATIVE_IMAGE% -jar %CC_PACKAGED_JAR% ^
	 -H:Name=Cloudchains-SPV ^
	 -H:Class=io.cloudchains.app.App ^
	 -H:+JNI ^
	 -H:+UseServiceLoaderFeature ^
	 -H:ReflectionConfigurationFiles=contrib/netty-reflection.json ^
	 -H:ReflectionConfigurationResources=META-INF/native-image/io.netty/transport/reflection-config.json ^
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
	 --verbose
