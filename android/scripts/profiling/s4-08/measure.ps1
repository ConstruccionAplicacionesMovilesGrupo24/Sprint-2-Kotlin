param(
    [Parameter(Mandatory=$true)][ValidateSet('before','after')][string]$Variant,
    [int]$Start = 1,
    [int]$Runs = 5,
    [ValidateSet('both','small','large')][string]$Mode = 'both'
)
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$repoFile = 'app/src/main/java/com/campusmeal/android/feature/restaurants/data/RestaurantRepository.kt'
$apk = 'app/build/outputs/apk/profile/app-profile.apk'
$pkg = 'com.campusmeal.android.profile'
$activity = "$pkg/com.campusmeal.android.profiling.RestaurantCacheCheckActivity"
$root = "docs/evidence/sprint4/s4-08-room-cache/controlled/$Variant"
$revision = if ($Variant -eq 'after') { 'd72b9ce' } else { 'd72b9ce^' }
$expected = (git rev-parse "${revision}:android/$repoFile").Trim()
if ($LASTEXITCODE -ne 0 -or (git hash-object $repoFile).Trim() -ne $expected) { throw 'Repository source does not match the requested variant.' }
if (!(Test-Path $apk)) { throw 'Build the profile APK first.' }
New-Item -ItemType Directory -Force $root | Out-Null
$hash = (Get-FileHash $apk -Algorithm SHA256).Hash
$meta = "$root/build.txt"
if (Test-Path $meta) {
    if (!(Select-String -Path $meta -SimpleMatch "apkSha256=$hash" -Quiet)) { throw 'APK differs from previous runs; do not mix builds.' }
} else {
    @("variant=$Variant", "repositoryBlob=$expected", "apkSha256=$hash", "head=$(git rev-parse HEAD)", "model=$(& $adb shell getprop ro.product.model)", "android=$(& $adb shell getprop ro.build.version.release)", "api=$(& $adb shell getprop ro.build.version.sdk)") | Set-Content $meta -Encoding ASCII
}
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }
& $adb shell cmd package compile -m speed -f $pkg
if ($LASTEXITCODE -ne 0) { throw 'Package compilation failed' }
$modes = if ($Mode -eq 'both') { @('small','large') } else { @($Mode) }
foreach ($m in $modes) {
    $dir = "$root/$m"
    New-Item -ItemType Directory -Force $dir | Out-Null
    if ($Start -eq 1) {
        # Three complete warmup workloads outside the recorded runs.
        for ($w=1; $w -le 3; $w++) {
            & $adb shell am force-stop $pkg
            & $adb logcat -c
            & $adb shell am start -W -n $activity --es cm.cache.mode $m | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Warmup launch failed' }
            $ok = $false
            for ($poll=0; $poll -lt 30; $poll++) {
                Start-Sleep -Seconds 1
                $log = & $adb logcat -d -s CampusMealCacheCheck
                if ($log -match "DONE mode=$m .* PASS") { $ok = $true; break }
                if ($log -match 'FAILED') { throw ($log -join "`n") }
            }
            if (!$ok) { throw 'Warmup did not finish' }
        }
    }
    for ($i=$Start; $i -lt ($Start+$Runs); $i++) {
        $run = 'run-{0:D2}' -f $i
        $local = "$dir/$run.perfetto-trace"
        if (Test-Path $local) { throw "Already exists: $local. Preserve earlier runs." }
        $remote = "/data/misc/perfetto-traces/s408-$Variant-$m-$run.perfetto-trace"
        Write-Host "$Variant $m $run" -ForegroundColor Cyan
        & $adb shell am force-stop $pkg
        & $adb logcat -c
        cmd.exe /c "`"$adb`" shell perfetto --background-wait -c - --txt -o $remote < scripts/profiling/campusmeal.pbtxt"
        if ($LASTEXITCODE -ne 0) { throw 'Perfetto background launch failed' }
        & $adb shell am start -W -n $activity --es cm.cache.mode $m | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Activity launch failed' }
        Start-Sleep -Seconds 31
        $log = & $adb logcat -d -s CampusMealCacheCheck
        $log | Set-Content "$dir/$run.log" -Encoding ASCII
        if (!($log -match "DONE mode=$m .* PASS")) { throw "Missing success log: $run" }
        & $adb pull $remote $local
        if ($LASTEXITCODE -ne 0) { throw 'Trace pull failed' }
        node scripts/profiling/sections.js $local | Set-Content "$dir/$run.csv" -Encoding ASCII
        if ($LASTEXITCODE -ne 0) { throw 'Trace parser failed' }
        node (Join-Path $PSScriptRoot 'summarize.mjs') "$dir/$run.csv" $m $Variant
        if ($LASTEXITCODE -ne 0) { throw 'Invalid trace: keep it separate before retrying' }
        & $adb shell rm $remote
    }
    $csvs = @(Get-ChildItem "$dir/run-*.csv" | Sort-Object Name | ForEach-Object { $_.FullName })
    node (Join-Path $PSScriptRoot 'summarize.mjs') $csvs $m $Variant | Tee-Object -FilePath "$dir/summary.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Summary failed' }
}
