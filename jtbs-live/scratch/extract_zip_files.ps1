Add-Type -AssemblyName System.IO.Compression.FileSystem

$zipPath = "c:\Users\JTBS-LIVE\Downloads\jtbs-live (1).zip"
$destDir = "c:\Users\JTBS-LIVE\Downloads\jtbs-live (1)\jtbs-live\scratch\zip_extracted"

if (!(Test-Path $destDir)) {
    New-Item -ItemType Directory -Path $destDir -Force | Out-Null
}

$zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)

$targets = @(
    "MainActivity.kt",
    "PlayerHealthMonitor.kt",
    "RecoveryManager.kt",
    "CustomLoadControl.kt",
    "PlayerFactory.kt"
)

foreach ($entry in $zip.Entries) {
    foreach ($target in $targets) {
        if ($entry.FullName.EndsWith($target)) {
            $destFile = Join-Path $destDir $target
            # Create subdirs if needed
            $parentDir = [System.IO.Path]::GetDirectoryName($destFile)
            if (!(Test-Path $parentDir)) {
                New-Item -ItemType Directory -Path $parentDir -Force | Out-Null
            }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destFile, $true)
            Write-Output "Extracted $target to $destFile"
        }
    }
}

$zip.Dispose()
