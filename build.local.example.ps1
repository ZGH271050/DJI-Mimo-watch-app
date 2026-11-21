# Copy this file to build.local.ps1 (which is git-ignored) and fill in the paths of your machine.
# build.ps1 dot-sources build.local.ps1 if it exists.
#
# $env:WATCH_OSMO_SDK = 'C:\Android\sdk'                 # build-tools 30.0.3 + platforms/android-30
# $env:R8_JAR         = 'C:\tools\r8.jar'               # optional; d8 from build-tools is the fallback
# $env:JBR_HOME       = 'C:\Program Files\Java\jdk-17' # optional; auto-detected otherwise
# $env:DEBUG_KEYSTORE = 'C:\keys\debug.keystore'        # optional; %USERPROFILE%\\.android\\debug.keystore
# $env:ADB            = 'C:\Android\platform-tools\\adb.exe'
