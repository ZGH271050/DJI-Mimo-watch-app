$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as ANSI/GBK
# unless there is a BOM, so non-ASCII comments can corrupt line parsing.

# Project root = this script's own folder, so the tree can be moved anywhere.
$realRoot = $PSScriptRoot
if (-not $realRoot) { $realRoot = Split-Path -Parent $MyInvocation.MyCommand.Path }
$realParent = Split-Path -Parent $realRoot
$projName = Split-Path -Leaf $realRoot

# A standalone r8.jar is optional: set R8_JAR, otherwise the d8 that ships with build-tools is used.

# Machine-local paths live in an untracked file, so the repository stays portable and carries
# nothing specific to one machine. Copy build.local.example.ps1 to build.local.ps1 and edit it.
$localFile = Join-Path $realRoot 'build.local.ps1'
if (Test-Path -LiteralPath $localFile) { . $localFile }

# The Android SDK used for this build: build-tools 30.0.3 + platforms/android-30
# (WifiNetworkSpecifier, MediaStore Q). Resolved in this order:
#   build.local.ps1 -> $env:WATCH_OSMO_SDK -> $env:ANDROID_SDK_ROOT -> $env:ANDROID_HOME
$realSdk = $env:WATCH_OSMO_SDK
if (-not $realSdk) { $realSdk = $env:ANDROID_SDK_ROOT }
if (-not $realSdk) { $realSdk = $env:ANDROID_HOME }
if (-not $realSdk) {
    Write-Host "ABORT: Android SDK not configured. Set WATCH_OSMO_SDK (build-tools 30.0.3 + platforms/android-30)." -ForegroundColor Red
    exit 1
}

# aapt2.exe / zipalign.exe are native Windows binaries that open paths through the
# ANSI code page. Here the console is UTF-8 (65001) while ANSI is GBK (936), so a
# project path containing Chinese characters makes them fail with
# "failed to open directory ... (2)". Java tools are unaffected.
# Fix: build through ASCII junctions pointing at the PARENT folders, so both the
# project and the SDK are reachable with pure-ASCII paths.
$root = $realRoot
$sdk = $realSdk
if ($realRoot -match '[^\x20-\x7E]') {
    $alias = Join-Path $env:TEMP 'wosmo-ascii'
    $linked = $null
    if (Test-Path $alias) {
        $item = Get-Item $alias -Force
        $linked = $item.Target
        if ($linked -is [array]) { $linked = $linked[0] }
    }
    if ($linked -ne $realParent) {
        # Directory.Delete removes only the reparse point. Never use
        # Remove-Item -Recurse on a junction: it would wipe the target folder.
        if (Test-Path $alias) { [System.IO.Directory]::Delete($alias, $false) }
        New-Item -ItemType Junction -Path $alias -Target $realParent | Out-Null
    }
    $root = Join-Path $alias $projName
    Write-Host ("ASCII build alias : {0}" -f $root) -ForegroundColor DarkGray
    Write-Host ("             ->     {0}" -f $realRoot) -ForegroundColor DarkGray
}
if ($sdk -match '[^\x20-\x7E]') {
    $aliasSdk = Join-Path $env:TEMP 'wasdk30'
    $linkedSdk = $null
    if (Test-Path $aliasSdk) {
        $item = Get-Item $aliasSdk -Force
        $linkedSdk = $item.Target
        if ($linkedSdk -is [array]) { $linkedSdk = $linkedSdk[0] }
    }
    if ($linkedSdk -ne $sdk) {
        if (Test-Path $aliasSdk) { [System.IO.Directory]::Delete($aliasSdk, $false) }
        New-Item -ItemType Junction -Path $aliasSdk -Target $sdk | Out-Null
    }
    $sdk = $aliasSdk
}

# The VS Code Java extension folder is renamed between releases (observed live:
# redhat.java-1.56.0-win32-x86_64 -> redhat.java-1.56.0-win32-x64), so resolve the bundled JBR by
# pattern instead of hardcoding a path that will rot.
$jbrCandidates = @()
if ($env:JBR_HOME) { $jbrCandidates += (Join-Path $env:JBR_HOME 'javac.exe') }
if ($env:JAVA_HOME) { $jbrCandidates += (Join-Path $env:JAVA_HOME 'bin\javac.exe') }
$jbrCandidates += @(Get-ChildItem (Join-Path $env:USERPROFILE '.vscode\extensions\redhat.java-*\jre\*\bin\javac.exe') -ErrorAction SilentlyContinue |
    ForEach-Object { $_.FullName })
$jbr = $null
foreach ($c in $jbrCandidates) {
    if ($c -and (Test-Path -LiteralPath $c)) { $jbr = Split-Path -Parent $c; break }
}
if (-not $jbr) {
    Write-Host "ABORT: no JDK found. Set JBR_HOME or JAVA_HOME (JBR 17+ / JDK 17+ with javac)." -ForegroundColor Red
    exit 1
}
$javaHome = Split-Path -Parent $jbr
$bt   = "$sdk\build-tools\30.0.3"
$plat = "$sdk\platforms\android-30\android.jar"
$r8   = $env:R8_JAR
if (-not $r8 -or -not (Test-Path -LiteralPath $r8)) { $r8 = "$bt\lib\d8.jar" }

$src  = "$root\src"
$work = "$root\build"
$out  = "$root\out"

$minSdk = 29
$targetSdk = 30

# ---------- 0. toolchain check ----------
Write-Host "=== [0] toolchain check ===" -ForegroundColor Cyan
$need = @{
    "javac"       = "$jbr\javac.exe"
    "jar"         = "$jbr\jar.exe"
    "aapt2"       = "$bt\aapt2.exe"
    "zipalign"    = "$bt\zipalign.exe"
    "apksigner"   = "$bt\apksigner.bat"
    "android.jar" = $plat
    "dexer"       = $r8
    "manifest"    = "$src\AndroidManifest.xml"
    "strings"     = "$src\res\values\strings.xml"
}
$missing = @()
foreach ($k in ($need.Keys | Sort-Object)) {
    if (Test-Path $need[$k]) { Write-Host ("  [OK]   {0}" -f $k) }
    else { Write-Host ("  [MISS] {0} -> {1}" -f $k, $need[$k]) -ForegroundColor Red; $missing += $k }
}
if ($missing.Count -gt 0) {
    Write-Host "ABORT: missing toolchain." -ForegroundColor Red
    exit 1
}

# ---------- 1. clean ----------
Write-Host ""
Write-Host "=== [1] clean ===" -ForegroundColor Cyan
foreach ($d in @($work, $out)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}
foreach ($d in @("res-c","gen","classes","dex")) {
    New-Item -ItemType Directory -Force -Path "$work\$d" | Out-Null
}
Write-Host "  ok"

# ---------- 2. aapt2 compile ----------
Write-Host ""
Write-Host "=== [2] aapt2 compile ===" -ForegroundColor Cyan
& "$bt\aapt2.exe" compile --dir "$src\res" -o "$work\res-c\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
Write-Host "  ok"

# ---------- 3. aapt2 link ----------
Write-Host ""
Write-Host "=== [3] aapt2 link ===" -ForegroundColor Cyan
& "$bt\aapt2.exe" link -o "$work\base.apk" --manifest "$src\AndroidManifest.xml" -I $plat --java "$work\gen" --min-sdk-version $minSdk --target-sdk-version $targetSdk --version-code 9 --version-name "0.2.7" "$work\res-c\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }
Write-Host "  ok -> base.apk"

# ---------- 4. javac ----------
Write-Host ""
Write-Host "=== [4] javac ===" -ForegroundColor Cyan
$javaFiles = @()
$javaFiles += (Get-ChildItem "$src\java" -Filter *.java -Recurse | ForEach-Object { $_.FullName })
$javaFiles += (Get-ChildItem "$work\gen" -Filter *.java -Recurse -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
Write-Host ("  sources: {0}" -f $javaFiles.Count)
# --release 8 makes "Java 8 only" a compiler-checked fact rather than a promise: without it
# javac 21 happily emits class files that lean on APIs that do not exist on Android.
& "$jbr\javac.exe" -encoding UTF-8 -nowarn --release 8 -cp $plat -d "$work\classes" $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
Write-Host "  ok"

# ---------- 5. d8 ----------
Write-Host ""
Write-Host "=== [5] d8 (dex) ===" -ForegroundColor Cyan
$classFiles = @(Get-ChildItem "$work\classes" -Filter *.class -Recurse | ForEach-Object { $_.FullName })
Write-Host ("  class files: {0}" -f $classFiles.Count)
& "$jbr\java.exe" -cp $r8 com.android.tools.r8.D8 --min-api $minSdk --output "$work\dex" $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
if (-not (Test-Path "$work\dex\classes.dex")) { throw "no classes.dex produced" }
Write-Host ("  ok -> classes.dex {0} KB" -f [math]::Round((Get-Item "$work\dex\classes.dex").Length/1KB,1))

# ---------- 6. package ----------
Write-Host ""
Write-Host "=== [6] package ===" -ForegroundColor Cyan
Copy-Item "$work\base.apk" "$work\unsigned.apk" -Force
Push-Location "$work\dex"
& "$jbr\jar.exe" uf "$work\unsigned.apk" classes.dex
$jarExit = $LASTEXITCODE
Pop-Location
if ($jarExit -ne 0) { throw "jar update failed" }
Write-Host "  ok"

# ---------- 7. zipalign ----------
Write-Host ""
Write-Host "=== [7] zipalign ===" -ForegroundColor Cyan
& "$bt\zipalign.exe" -f -p 4 "$work\unsigned.apk" "$work\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
Write-Host "  ok"

# ---------- 8. sign ----------
Write-Host ""
Write-Host "=== [8] sign ===" -ForegroundColor Cyan
# apksigner.bat resolves java via JAVA_HOME; the machine's JAVA_HOME points at a
# removed Corretto install, so override it with the JDK we actually have.
$env:JAVA_HOME = $javaHome
$ks = Join-Path $env:USERPROFILE ".android\debug.keystore"
if ($env:DEBUG_KEYSTORE) { $ks = $env:DEBUG_KEYSTORE }
if (-not (Test-Path $ks)) { throw "missing debug.keystore" }
$final = "$out\OsmoWatch.apk"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --v1-signing-enabled true --v2-signing-enabled true --out $final "$work\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

# ---------- 9. report ----------
Write-Host ""
Write-Host "=== [9] RESULT ===" -ForegroundColor Green
& "$bt\apksigner.bat" verify --print-certs $final 2>&1 | Select-Object -First 4
Write-Host ""
Write-Host ("  APK  : {0}" -f $final)
if ($root -ne $realRoot) { Write-Host ("  real : {0}" -f (Join-Path $realRoot 'out\OsmoWatch.apk')) }
Write-Host ("  size : {0} KB" -f [math]::Round((Get-Item $final).Length/1KB,1))
Write-Host ""
Write-Host "  --- manifest ---"
& "$bt\aapt2.exe" dump badging $final 2>&1 | Select-String -Pattern 'package:|launchable|sdkVersion|targetSdk|application-label' | Select-Object -First 8
Write-Host ""
Write-Host "  --- native code check (must be empty) ---"
$bad = & "$bt\aapt2.exe" dump badging $final 2>&1 | Select-String -Pattern 'native-code'
if ($bad) { Write-Host ("  WARNING: {0}" -f $bad) -ForegroundColor Red } else { Write-Host "  OK: no native-code" }
Write-Host ""
& "$jbr\jar.exe" tf $final | Select-String -Pattern 'classes.dex|AndroidManifest|resources.arsc'
exit 0
