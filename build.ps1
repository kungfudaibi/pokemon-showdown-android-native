$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'D:\Android_SDK' }
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'D:\Android_stdio\jbr' }
$jdk = Join-Path $javaHome 'bin'
$env:JAVA_HOME = $javaHome
$env:Path = "$jdk;$env:Path"
$tools = Join-Path $sdk 'build-tools\36.0.0'
$platform = Join-Path $sdk 'platforms\android-36.1\android.jar'
$build = Join-Path $root 'build'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$signing = Join-Path $root 'signing'
$keystore = Join-Path $signing 'dev.keystore'
New-Item -ItemType Directory -Force -Path $classes, $dex, $signing | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
& "$jdk\javac.exe" -encoding UTF-8 -source 8 -target 8 -classpath $platform -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
$classFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
& "$tools\d8.bat" --lib $platform --min-api 26 --output $dex $classFiles
if ($LASTEXITCODE -ne 0) { throw 'Dex compilation failed' }
$unsigned = Join-Path $build 'unsigned.apk'
$aligned = Join-Path $build 'aligned.apk'
$output = Join-Path $build 'showdown-native.apk'
$resources = Join-Path $build 'resources.zip'
& "$tools\aapt2.exe" compile --dir (Join-Path $root 'res') -o $resources
if ($LASTEXITCODE -ne 0) { throw 'Android resource compilation failed' }
& "$tools\aapt2.exe" link -o $unsigned --manifest (Join-Path $root 'AndroidManifest.xml') -I $platform -A (Join-Path $root 'assets') -R $resources --min-sdk-version 26 --target-sdk-version 35
if ($LASTEXITCODE -ne 0) { throw 'APK resource linking failed' }
Push-Location $dex
try { & "$jdk\jar.exe" uf $unsigned classes.dex }
finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'Adding classes.dex failed' }
& "$tools\zipalign.exe" -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
if (-not (Test-Path -LiteralPath $keystore)) {
    & "$jdk\keytool.exe" -genkeypair -keystore $keystore -storepass android -keypass android -alias showdown-native-dev -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Showdown Native Dev, O=Local Test, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'Generating signing key failed' }
}
& "$tools\apksigner.bat" sign --ks $keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias showdown-native-dev --out $output $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
& "$tools\apksigner.bat" verify --verbose $output
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
Get-Item -LiteralPath $output | Select-Object FullName, Length
