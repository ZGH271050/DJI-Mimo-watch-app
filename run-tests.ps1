$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as ANSI/GBK
# unless there is a BOM, so non-ASCII comments can corrupt line parsing.

# Runs every desktop-JVM self test in the project.
#
# These tests are the only real verification available: the watch and the camera are not attached
# to this machine, so protocol logic is proven against the hardware captures checked into
# reference\osmosis\app\src\test\resources\manifests\, and the pure-logic layers are exercised on
# the desktop. Anything that needs a radio (BLE, Wi-Fi join, the real UDP datalink) is explicitly
# out of scope here and listed as such at the end.

$root = $PSScriptRoot
# Resolve the JBR by pattern: the VS Code Java extension folder gets renamed between releases.
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
$javac = "$jbr\javac.exe"
$java = "$jbr\java.exe"
$work = Join-Path $env:TEMP 'wosmo-tests'
$srcRoot = Join-Path $root 'src\java\com\watch\osmo'
$testRoot = Join-Path $root 'test'

if (-not (Test-Path $javac)) { Write-Host "ABORT: javac not found at $javac" -ForegroundColor Red; exit 1 }

# javac and the JVM write their diagnostics in the console code page (GBK here). Decode them as
# such so compiler errors are readable instead of turning into mojibake.
try { [Console]::OutputEncoding = [System.Text.Encoding]::GetEncoding(936) } catch { }

if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Glob-Sources([string]$pattern) {
    $p = Join-Path $srcRoot $pattern
    $dir = Split-Path $p -Parent
    $leaf = Split-Path $p -Leaf
    if (-not (Test-Path $dir)) { return @() }
    return @(Get-ChildItem -Path $dir -Filter $leaf -File -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName })
}

# name = test class; sources = globs relative to the package root
$tests = @(
    @{ name = 'DumlSelfTest';       file = 'DumlSelfTest.java';       sources = @('duml\*.java') }
    @{ name = 'ManifestSelfTest';   file = 'ManifestSelfTest.java';   sources = @('proto\*.java') }
    @{ name = 'HttpSelfTest';       file = 'HttpSelfTest.java';       sources = @('net\HttpFile.java', 'net\Downloader.java') }
    @{ name = 'BleSelfTest';        file = 'BleSelfTest.java';        sources = @('ble\BleAdvert.java', 'ble\BleConstants.java', 'core\CameraInfo.java') }
    @{ name = 'DatalinkSelfTest';   file = 'DatalinkSelfTest.java';   sources = @('duml\*.java', 'net\UdpPacket.java', 'net\Datalink.java', 'net\ManifestAssembler.java') }
    @{ name = 'PipelineSelfTest';   file = 'PipelineSelfTest.java';   sources = @('duml\*.java', 'proto\*.java', 'net\UdpPacket.java', 'net\Datalink.java', 'net\ManifestAssembler.java') }
    @{ name = 'RoundGeometryTest';  file = 'RoundGeometryTest.java';  sources = @('ui\Round.java') }
)

$results = @()
foreach ($t in $tests) {
    $testFile = Join-Path $testRoot $t.file
    Write-Host ""
    Write-Host ("=== {0} ===" -f $t.name) -ForegroundColor Cyan
    if (-not (Test-Path $testFile)) {
        Write-Host "  SKIP: no test file" -ForegroundColor Yellow
        $results += [pscustomobject]@{ Test = $t.name; Compile = 'skip'; Run = 'skip'; Note = 'missing test file' }
        continue
    }
    $sources = @()
    foreach ($g in $t.sources) { $sources += (Glob-Sources $g) }
    $sources += $testFile
    if ($sources.Count -le 1) {
        Write-Host "  SKIP: no sources matched" -ForegroundColor Yellow
        $results += [pscustomobject]@{ Test = $t.name; Compile = 'skip'; Run = 'skip'; Note = 'no sources' }
        continue
    }

    $outDir = Join-Path $work $t.name
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $log = Join-Path $work ("{0}.log" -f $t.name)

    $compileOut = & $javac -encoding UTF-8 -nowarn -d $outDir $sources 2>&1
    $compileCode = $LASTEXITCODE
    if ($compileCode -ne 0) {
        Write-Host "  COMPILE FAILED" -ForegroundColor Red
        $compileOut | Select-Object -First 12 | ForEach-Object { Write-Host ("    " + $_) }
        $results += [pscustomobject]@{ Test = $t.name; Compile = 'fail'; Run = 'n/a'; Note = 'see output' }
        ($compileOut -join "`n") | Set-Content -LiteralPath $log -Encoding UTF8
        continue
    }
    Write-Host ("  compiled {0} sources" -f $sources.Count)

    # Run from the project root so tests that look for the reference fixtures find them.
    Push-Location $root
    $runOut = & $java -cp $outDir $t.name 2>&1
    $runCode = $LASTEXITCODE
    Pop-Location
    ($runOut -join "`n") | Set-Content -LiteralPath $log -Encoding UTF8
    $summary = ($runOut | Select-String -Pattern 'SUMMARY' | Select-Object -Last 1)
    if (-not $summary) { $summary = ($runOut | Select-Object -Last 1) }
    Write-Host ("  -> {0}" -f $summary)
    $runState = if ($runCode -eq 0) { 'pass' } else { 'FAIL' }
    if ($runCode -eq 0) {
        Write-Host "  PASS" -ForegroundColor Green
    } else {
        Write-Host "  FAIL (exit $runCode) - full log: $log" -ForegroundColor Red
        $runOut | Select-Object -Last 15 | ForEach-Object { Write-Host ("    " + $_) }
    }
    $results += [pscustomobject]@{ Test = $t.name; Compile = 'ok'; Run = $runState; Note = "$summary" }
}

Write-Host ""
Write-Host "=================== SUMMARY ===================" -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String | Write-Host
$failed = @($results | Where-Object { $_.Run -eq 'FAIL' -or $_.Compile -eq 'fail' })
Write-Host ("logs: {0}" -f $work)
Write-Host ""
Write-Host "NOT covered by these tests (needs hardware):" -ForegroundColor Yellow
Write-Host "  - BLE advertising reception, GATT connect, MTU negotiation, pairing approval"
Write-Host "  - WifiNetworkSpecifier join of the camera AP"
Write-Host "  - real UDP datalink to 192.168.2.1 and its keepalive"
Write-Host "  - on-device rendering and touch on the round panel"
if ($failed.Count -gt 0) {
    Write-Host ""
    Write-Host ("{0} test(s) failed" -f $failed.Count) -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host "ALL DESKTOP TESTS PASSED" -ForegroundColor Green
exit 0
