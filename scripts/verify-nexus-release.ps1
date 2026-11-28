param(
    [Parameter(Mandatory)][string]$Repo,
    [Parameter(Mandatory)][string]$Source,
    [Parameter(Mandatory)][string]$Output,
    [Parameter(Mandatory)][string]$VersionName,
    [Parameter(Mandatory)][int]$VersionCode,
    [Parameter(Mandatory)][string]$StableApk,
    [string]$BetaApk,
    [switch]$SmallApks
)

$ErrorActionPreference = 'Stop'
$package = 'app.nexusmedia.tv'
$signer = '33becd569fc66b718a9b4988dd43b04982f19c8fd3c2c867e22bf433d4a97b8a'
$state = Join-Path $Repo '.gradle/nexus-upstream'
$sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk'
$tools = Join-Path $Repo '.gradle/nexus-sdk/build-tools/35.0.0'
$env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
$env:ANDROID_HOME = Join-Path $Repo '.gradle/nexus-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $state 'android'
$env:ANDROID_EMULATOR_HOME = $env:ANDROID_USER_HOME
$env:ANDROID_AVD_HOME = Join-Path $state 'avd'
$env:GRADLE_USER_HOME = Join-Path $Repo '.gradle/nexus-user'
$env:TEMP = Join-Path $Repo '.gradle/nexus-temp'
$env:TMP = $env:TEMP

function Invoke-Checked([string]$Command, [string[]]$Parameters) {
    & $Command @Parameters
    if ($LASTEXITCODE) { throw "$([IO.Path]::GetFileName($Command)) failed with exit code $LASTEXITCODE." }
}

function Confirm-ApkSignature([string]$Apk) {
    $certs = Invoke-Checked "$tools/apksigner.bat" @('verify', '--print-certs', $Apk)
    if (@($certs -match 'certificate SHA-256 digest:').Count -ne 1 -or
        !($certs -match "certificate SHA-256 digest: $signer$")) {
        throw 'APK signer does not match the existing Nexus Media installation.'
    }
}

Get-ChildItem Env: | Where-Object { $_.Name -match '^NUVIO_RELEASE_|^GH_TOKEN$|^GITHUB_TOKEN$|^SENTRY_AUTH_TOKEN$' } | ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }

$init = Join-Path $PSScriptRoot 'nexus-release.init.gradle'
$nativeArguments = @()
$testArguments = @(
    ':app:testFullReleaseUnitTest', '--rerun',
    '--tests', 'com.nuvio.tv.data.livetv.XtreamClientTest',
    '--tests', 'com.nuvio.tv.updater.*'
)
if ($SmallApks) {
    $ndk = Join-Path $state 'toolchains/android-ndk-r29'
    $strip = Join-Path $ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
    if (!(Test-Path -LiteralPath $strip -PathType Leaf)) {
        throw 'Install the pinned Android NDK r29 in the release toolchains folder before building smaller APKs.'
    }
    $nativeArguments += "-PnexusReleaseNdkPath=$ndk"
    $testArguments += @('--tests', 'com.nuvio.tv.data.livetv.*', '--tests', 'com.nuvio.tv.core.server.XtreamPhoneSetupServerTest')
}
Push-Location -LiteralPath $Source
$originalPath = $env:Path
$originalTmdbKey = $env:NEXUS_TMDB_API_KEY
# AGP adds PATH entries to java.library.path for JVM unit tests.
$env:Path = @(
    (Join-Path $env:JAVA_HOME 'bin')
    (Join-Path $env:SystemRoot 'System32')
    $env:SystemRoot
    (Join-Path $env:ProgramFiles 'Git/cmd')
) -join ';'
try {
    $tmdbKeyFile = Join-Path $Repo '.nexus-signing/tmdb-api-key.dpapi'
    if (Test-Path -LiteralPath $tmdbKeyFile -PathType Leaf) {
        $secureTmdbKey = ConvertTo-SecureString ((Get-Content -LiteralPath $tmdbKeyFile -Raw).Trim())
        try {
            $env:NEXUS_TMDB_API_KEY = [Net.NetworkCredential]::new('', $secureTmdbKey).Password
            if ($env:NEXUS_TMDB_API_KEY -cnotmatch '^[0-9a-f]{32}$') {
                throw 'The configured TMDB application key has an invalid format.'
            }
        } finally {
            $secureTmdbKey.Dispose()
        }
    }
    $hostMemory = Get-CimInstance -ClassName Win32_OperatingSystem -Property FreeVirtualMemory
    $availableCommitMiB = [int]($hostMemory.FreeVirtualMemory / 1KB)
    if ($availableCommitMiB -lt (9 * 1024)) {
        throw "Android release optimization needs 9 GiB of available Windows commit (currently ${availableCommitMiB} MiB). Retry when more memory is free."
    }
    Write-Output "Starting release optimization with ${availableCommitMiB} MiB of available Windows commit."

    $gradleArguments = @(
        '--init-script', $init, '--no-daemon', '--no-build-cache', '--console=plain', '--configure-on-demand', '--max-workers=1',
        '-PnexusUniversalApk=true', '-Pkotlin.compiler.execution.strategy=in-process',
        '-Pandroid.builder.sdkDownload=false'
    ) + $nativeArguments
    $gradle = Join-Path $Source 'gradlew.bat'
    # Android includes the processor count in the optimizer task inputs.
    $jvmOptions = '-XX:MaxMetaspaceSize=640m -XX:ActiveProcessorCount=6 -Djava.util.concurrent.ForkJoinPool.common.parallelism=3 -Dfile.encoding=UTF-8'
    $optimizeJvm = "-Dorg.gradle.jvmargs=-Xmx5120m $jvmOptions"
    Invoke-Checked $gradle ($gradleArguments + $optimizeJvm + @(
        ':app:minifyFullReleaseWithR8', ':app:optimizeFullReleaseResources', ':app:lintVitalFullRelease'
    ) + $testArguments)
    $assembleJvm = "-Dorg.gradle.jvmargs=-Xmx3584m $jvmOptions"
    Invoke-Checked $gradle ($gradleArguments + $assembleJvm + @(':app:assembleFullRelease', '-x', ':app:lintVitalFullRelease'))
} finally {
    $env:NEXUS_TMDB_API_KEY = $originalTmdbKey
    $env:Path = $originalPath
    Pop-Location
}

$apkDir = Join-Path $Source 'app/build/outputs/apk/full/release'
$metadata = Get-Content -LiteralPath (Join-Path $apkDir 'output-metadata.json') -Raw | ConvertFrom-Json
$entry = $metadata.elements | Select-Object -First 1
if (@($metadata.elements).Count -ne 1 -or $metadata.applicationId -ne $package -or
    $entry.versionCode -ne $VersionCode -or $entry.versionName -ne $VersionName -or
    @($entry.filters).Count -or $entry.outputFile -notmatch '-unsigned\.apk$') {
    throw 'Gradle did not produce the expected universal unsigned Nexus Media APK.'
}

$unsignedApk = Join-Path $apkDir $entry.outputFile
$universalName = 'Nexus-Media.apk'
$intelName = 'Nexus-Media-x86-x86_64.apk'
$variants = [ordered]@{
    $universalName = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
}
if ($SmallApks) {
    $variants['Nexus-Media-arm64-v8a.apk'] = @('arm64-v8a')
    $variants['Nexus-Media-armeabi-v7a.apk'] = @('armeabi-v7a')
    # The existing TV updater matches ABI names by substring. One Intel APK
    # keeps x86 and x86_64 devices on their native libraries in any asset order.
    $variants[$intelName] = @('x86', 'x86_64')
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($unsignedApk)
    try {
        $libraries = @($archive.Entries.FullName | Where-Object { $_ -match '^lib/[^/]+/.+' })
    } finally {
        $archive.Dispose()
    }
    $symbols = Join-Path $Source 'app/build/outputs/native-debug-symbols/fullRelease/native-debug-symbols.zip'
    Copy-Item -LiteralPath $symbols -Destination (Join-Path $Output 'native-debug-symbols.zip') -Force
}

$finalApk = Join-Path $Output $universalName
$alignedApk = Join-Path $Output 'nexus-aligned.apk'
$filteredApk = Join-Path $Output 'nexus-filtered.apk'
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1')
$secret = Get-Content -LiteralPath (Join-Path $Repo '.nexus-signing/password.dpapi') -Raw | ConvertTo-SecureString
try {
    $env:NEXUS_LOCAL_SIGNING_PASSWORD = [PSCredential]::new('nexus-media', $secret).GetNetworkCredential().Password
    foreach ($name in $variants.Keys) {
        $sourceApk = $unsignedApk
        if ($name -ne $universalName) {
            Copy-Item -LiteralPath $unsignedApk -Destination $filteredApk -Force
            $remove = @($libraries | Where-Object { $variants[$name] -notcontains $_.Split('/')[1] })
            Invoke-Checked "$tools/aapt.exe" (@('remove', $filteredApk) + $remove) | Out-Null
            $sourceApk = $filteredApk
        }
        Invoke-Checked "$tools/zipalign.exe" @('-f', '-P', '16', '4', $sourceApk, $alignedApk)
        $signedApk = Join-Path $Output $name
        Invoke-Checked "$tools/apksigner.bat" @(
            'sign', '--ks', (Join-Path $Repo '.nexus-signing/nexus-media.p12'),
            '--ks-key-alias', 'nexus-media', '--ks-pass', 'env:NEXUS_LOCAL_SIGNING_PASSWORD',
            '--key-pass', 'env:NEXUS_LOCAL_SIGNING_PASSWORD', '--out', $signedApk, $alignedApk
        )
    }
} finally {
    Remove-Item Env:NEXUS_LOCAL_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    $secret.Dispose()
    Remove-Item -LiteralPath $alignedApk, $filteredApk -ErrorAction SilentlyContinue
}

foreach ($name in $variants.Keys) {
    $signedApk = Join-Path $Output $name
    Confirm-ApkSignature $signedApk
    Invoke-Checked "$tools/zipalign.exe" @('-c', '-P', '16', '4', $signedApk)
    $badging = Invoke-Checked "$tools/aapt.exe" @('dump', 'badging', $signedApk)
    $nativeCode = 'native-code: ' + (($variants[$name] | ForEach-Object { "'$_'" }) -join ' ')
    if (!($badging -match "^package: name='$package' versionCode='$VersionCode' versionName='$([regex]::Escape($VersionName))'") -or
        $badging -notcontains $nativeCode) {
        throw "$name does not match the expected version and TV architectures."
    }
    $length = (Get-Item -LiteralPath $signedApk).Length
    if ($name -ne $universalName -and $length -ge (Get-Item -LiteralPath $finalApk).Length) {
        throw "$name must be smaller than the universal APK."
    }
    Write-Output "Verified $name ($length bytes)."
}

$adb = Join-Path $sdk 'platform-tools/adb.exe'
$serial = 'emulator-5584'
$avd = 'NexusReleaseCheck'
New-Item -ItemType Directory -Path $env:ANDROID_USER_HOME, $env:ANDROID_AVD_HOME -Force | Out-Null
if (!(Test-Path -LiteralPath (Join-Path $env:ANDROID_AVD_HOME "$avd.ini"))) {
    'no' | & "$sdk/cmdline-tools/latest/bin/avdmanager.bat" create avd --name $avd --package 'system-images;android-36;android-tv;x86' --device tv_720p
    if ($LASTEXITCODE) { throw 'Could not create the Android TV release test device.' }
}
if ((& $adb devices) -match "^$serial\s") {
    throw "The release test emulator port $serial is already in use."
}

function Get-InstalledPackage {
    Invoke-Checked $adb @('-s', $serial, 'shell', 'dumpsys', 'package', $package)
}

function Confirm-AppLaunch {
    $deadline = [DateTime]::UtcNow.AddSeconds(85)
    do {
        $launch = & $adb -s $serial shell am start -W -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n "$package/com.nuvio.tv.launcher.AppIconDefault"
        if (($launch -match '^Status: ok') -and ((& $adb -s $serial shell dumpsys activity activities) -match "topResumedActivity=.*$package/")) {
            $dump = & $adb -s $serial shell uiautomator dump --compressed /data/local/tmp/nexus-release-ui.xml
            if ($dump -match 'dumped to') {
                $ui = (& $adb -s $serial shell cat /data/local/tmp/nexus-release-ui.xml) -join ''
                if ($ui -match 'package="app\.nexusmedia\.tv"') { return }
            }
        }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Nexus Media did not launch its UI on Android TV.'
}

$emulatorArgs = @(
    '-avd', $avd, '-port', '5584', '-no-window', '-no-snapshot', '-no-boot-anim',
    '-cores', '4', '-memory', '2048', '-gpu', 'host'
)
$emulatorLog = Join-Path $Output 'emulator.log'
$emulatorErrors = Join-Path $Output 'emulator-error.log'
$emulator = Start-Process -FilePath "$sdk/emulator/emulator.exe" -ArgumentList $emulatorArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput $emulatorLog -RedirectStandardError $emulatorErrors
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(360)
    do {
        if ((& $adb devices) -match "^$serial\s+device$") {
            $boot = ((& $adb -s $serial shell getprop sys.boot_completed) -join "").Trim()
            if ($boot -eq '1') { break }
        }
        if ($emulator.HasExited) { throw 'The Android TV release test device exited during boot.' }
        Start-Sleep -Seconds 3
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($boot -ne '1') { throw 'The dedicated Android TV emulator did not finish booting in six minutes.' }
    if ((& $adb -s $serial emu avd name | Select-Object -First 1) -ne $avd) {
        throw 'Could not verify the identity of the dedicated Android TV release test device.'
    }
    Write-Output 'The dedicated Android TV test device is ready.'

    foreach ($baseline in @($StableApk, $BetaApk) | Where-Object { $_ } | Select-Object -Unique) {
        Confirm-ApkSignature $baseline
        if ((& $adb -s $serial shell pm path $package) -match '^package:') {
            Invoke-Checked $adb @('-s', $serial, 'uninstall', $package)
        }
        Invoke-Checked $adb @('-s', $serial, 'install', $baseline)
        Confirm-AppLaunch
        $before = Get-InstalledPackage
        $id = $before | Where-Object { $_ -match '^\s+appId=\d+$' } | Select-Object -First 1
        $installedAt = $before | Where-Object { $_ -match '^\s+firstInstallTime=' } | Select-Object -First 1
        if (!$id -or !$installedAt) { throw 'Could not read the original Android app installation.' }

        $upgradeApk = $finalApk
        if ($SmallApks -and $baseline -eq $BetaApk) {
            $upgradeApk = Join-Path $Output $intelName
        }
        Invoke-Checked $adb @('-s', $serial, 'install', '-r', $upgradeApk)
        Confirm-AppLaunch
        $after = Get-InstalledPackage
        if ($after -notcontains $id -or $after -notcontains $installedAt -or
            !($after -match "^\s+versionCode=$VersionCode\b") -or
            !($after -match "^\s+versionName=$([regex]::Escape($VersionName))$")) {
            throw 'The APK did not upgrade the existing Android TV installation.'
        }
        Write-Output "TV upgrade passed from $((Split-Path (Split-Path $baseline -Parent) -Leaf)) to $VersionName using $((Split-Path $upgradeApk -Leaf))."
    }
} finally {
    if (((& $adb devices) -match "^$serial\s") -and ((& $adb -s $serial emu avd name | Select-Object -First 1) -eq $avd)) {
        & $adb -s $serial emu kill 2>$null | Out-Null
    }
}
