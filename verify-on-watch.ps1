# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as ANSI/GBK unless there is a
# BOM, so non-ASCII comments or strings can corrupt line parsing.
#
# Acceptance run on the real watch. THIS INSTALLS THE APK.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-on-watch.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-on-watch.ps1 -Serial 192.168.1.50:42251
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\verify-on-watch.ps1 -SkipInstall
#
# The Galaxy Watch7 exposes adb over wireless debugging on a RANDOM port, and both its IP and port
# change between reboots, so the serial is auto-detected from "adb devices" unless -Serial is given.

# NOTE: param() must be the first executable statement in the file - only comments may precede it.
param(
    [string]$Serial = "",
    [string]$Apk = "",
    [string]$Adb = "",
    [switch]$SkipInstall,
    [int]$LaunchWaitSec = 14
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

$root = $PSScriptRoot
if (-not $Apk) { $Apk = Join-Path $root 'out\OsmoWatch.apk' }
$pkg = 'com.watch.osmo'
$activity = 'com.watch.osmo.ui.MainActivity'
$evidence = Join-Path $root 'out\evidence'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

function Say([string]$m, [string]$color = 'Gray') { Write-Host $m -ForegroundColor $color }

# ---------------------------------------------------------------- adb
# Resolve adb: the工具箱 path contains Chinese characters, so it is assembled from code points to
# keep this file ASCII-only. An empty -Adb means "find it".
if (-not $Adb) {
    # D:\<软件>\<安卓手表>ADB<实用工具><箱>\AndroidWatch_ADB_ToolBox\adb\adb.exe
    $dir = 'D:\' + [char]0x8F6F + [char]0x4EF6 + '\' + [char]0x5B89 + [char]0x5353 + [char]0x624B + [char]0x8868 + 'ADB' + [char]0x5B9E + [char]0x7528 + [char]0x5DE5 + [char]0x5177 + [char]0x7BB1
    $candidate = $dir + '\AndroidWatch_ADB_ToolBox\adb\adb.exe'
    if (Test-Path -LiteralPath $candidate) {
        $Adb = $candidate
    } elseif (Test-Path -LiteralPath ($dir + '\adb.exe')) {
        $Adb = $dir + '\adb.exe'
    } elseif (Get-Command adb -ErrorAction SilentlyContinue) {
        $Adb = 'adb'
    }
}
if (-not $Adb -or (-not (Get-Command $Adb -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath $Adb))) {
    Say "ABORT: adb not found. Pass -Adb <path to adb.exe>." 'Red'
    exit 1
}
Say ("adb    : {0}" -f $Adb) 'DarkGray'

& $Adb start-server 2>&1 | Out-Null
$deviceLines = & $Adb devices 2>&1 | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
if (-not $Serial) {
    if ($deviceLines.Count -eq 0) {
        Say "ABORT: no device. Connect the watch (wireless debugging) first; 'adb devices' shows the IP:port." 'Red'
        exit 1
    }
    $Serial = ($deviceLines[0] -split '\s+')[0]
}
Say ("device : {0}" -f $Serial) 'Cyan'
$dev = @('-s', $Serial)

$model = (& $Adb @dev shell getprop ro.product.model) -join ''
$sdk = (& $Adb @dev shell getprop ro.build.version.sdk) -join ''
$abi = (& $Adb @dev shell getprop ro.product.cpu.abilist) -join ''
Say ("model  : {0}   sdk {1}   abi {2}" -f $model.Trim(), $sdk.Trim(), $abi.Trim())
if ($abi -notmatch 'armeabi-v7a') {
    Say "WARNING: expected a 32-bit armeabi-v7a watch." 'Yellow'
}

# ---------------------------------------------------------------- install
if (-not $SkipInstall) {
    if (-not (Test-Path -LiteralPath $Apk)) { Say "ABORT: APK not found: $Apk" 'Red'; exit 1 }
    Say ""
    Say "=== [1] install ===" 'Cyan'
    Say ("apk    : {0}  ({1:N1} KB)" -f $Apk, ((Get-Item -LiteralPath $Apk).Length / 1KB))
    # -r keeps data on reinstall; the first install of a differently-signed build needs -r removed.
    $installOut = & $Adb @dev install -r $Apk 2>&1
    $installOut | ForEach-Object { Say ("  " + $_) }
    if (($installOut -join "`n") -notmatch 'Success') {
        Say "install FAILED - see the output above (INSTALL_FAILED_UPDATE_INCOMPATIBLE means an" 'Red'
        Say "existing build signed with another key is installed; uninstall it first)." 'Red'
        exit 1
    }
} else {
    Say ""
    Say "=== [1] install skipped (reusing the installed build) ===" 'Cyan'
}

# ---------------------------------------------------------------- permissions
Say ""
Say "=== [2] grant runtime permissions ===" 'Cyan'
$perms = @(
    'android.permission.BLUETOOTH_SCAN',
    'android.permission.BLUETOOTH_CONNECT',
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.NEARBY_WIFI_DEVICES',
    'android.permission.POST_NOTIFICATIONS'
)
foreach ($p in $perms) {
    $r = & $Adb @dev shell "pm grant $pkg $p" 2>&1
    Say ("  {0} {1}" -f $p, ($(if ($r) { $r } else { 'ok' })))
}

# ---------------------------------------------------------------- prepare the panel
Say ""
Say "=== [3] wake the panel ===" 'Cyan'
& $Adb @dev shell "svc power stayon true" 2>&1 | Out-Null
& $Adb @dev shell "input keyevent 224" 2>&1 | Out-Null
Start-Sleep -Seconds 1

# A screenshot taken while the panel is off is a fully black PNG, which is easily mistaken for a
# broken app; always check the wakefulness right next to the capture.
$wake = (& $Adb @dev shell "dumpsys power | grep -m1 mWakefulness=") -join ''
Say ("  {0}" -f $wake.Trim())

# ---------------------------------------------------------------- launch
Say ""
Say "=== [4] launch ===" 'Cyan'
& $Adb @dev logcat -c 2>&1 | Out-Null
& $Adb @dev shell "am force-stop $pkg" 2>&1 | Out-Null
& $Adb @dev shell "am start -n $activity" 2>&1 | ForEach-Object { Say ("  " + $_) }
Start-Sleep -Seconds $LaunchWaitSec

$focus = (& $Adb @dev shell "dumpsys window | grep -m1 mCurrentFocus") -join ''
Say ("  focus: {0}" -f $focus.Trim())
$pid = ((& $Adb @dev shell "pidof $pkg") -join '').Trim()
if ($pid) { Say ("  process alive, pid {0}" -f $pid) 'Green' } else { Say "  process NOT running - it crashed" 'Red' }

# ---------------------------------------------------------------- evidence
Say ""
Say "=== [5] collect evidence ===" 'Cyan'
$logFile = Join-Path $evidence ("logcat-{0}.txt" -f $stamp)
& $Adb @dev logcat -d -v time -s OsmoWatch:V AndroidRuntime:E > $logFile 2>&1
Say ("  logcat   : {0}" -f $logFile)

$pngRemote = "/sdcard/osmo-$stamp.png"
$pngLocal = Join-Path $evidence ("screen-{0}.png" -f $stamp)
& $Adb @dev shell "screencap -p $pngRemote" 2>&1 | Out-Null
& $Adb @dev pull $pngRemote $pngLocal 2>&1 | Out-Null
if (Test-Path -LiteralPath $pngLocal) {
    Say ("  screenshot: {0}  ({1:N0} KB)" -f $pngLocal, ((Get-Item -LiteralPath $pngLocal).Length / 1KB))
    Say "  (a black ~2-5 KB PNG means the panel was off when it was taken, not that the app is broken)" 'Yellow'
} else {
    Say "  screenshot FAILED" 'Red'
}

$appLog = (& $Adb @dev shell "run-as $pkg cat files/osmo.log" 2>&1) -join "`n"
if ($appLog -and $appLog -notmatch 'run-as:') {
    $appLogFile = Join-Path $evidence ("applog-{0}.txt" -f $stamp)
    $appLog | Set-Content -LiteralPath $appLogFile -Encoding UTF8
    Say ("  in-app log: {0}" -f $appLogFile)
} else {
    Say "  in-app log: not readable via run-as (expected on a release/non-debuggable build;" 'Yellow'
    Say "              use the app's own 'export log' entry instead)" 'Yellow'
}

Say ""
Say "=== what to look at ===" 'Cyan'
Say "  1. device screen : title, a 'scan' button, and the last log lines"
Say "  2. tap scan      : the camera should appear within ~10 s (power it on, Bluetooth on)"
Say "  3. tap the camera: it should say to confirm on the camera screen, which shows OSMO"
Say "  4. after confirm : Wi-Fi joins the camera AP, then the file list appears"
Say "  5. pick files    : 'download selected', then watch the queue screen"
Say ""
Say "  If step 4 never joins: dump the Wi-Fi state and send it back -"
Say ("    {0} -s {1} shell `"dumpsys wifi | grep -i -m20 'SSID\|state'`"" -f $Adb, $Serial)
Say ""
Say "evidence folder: $evidence"
