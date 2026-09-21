$logPath = "C:\Users\JTBS-LIVE\.gemini\antigravity\brain\157d68a2-5496-41de-b309-2a8c53de405a\.system_generated\logs\transcript_full.jsonl"
$targetFiles = @(
    "DecoderPlaybackService.kt",
    "MainActivity.kt",
    "PlayerHealthMonitor.kt",
    "RecoveryManager.kt"
)

# Read transcript line by line
Get-Content $logPath | ForEach-Object {
    try {
        $obj = $_ | ConvertFrom-Json
        if ($obj.type -eq "PLANNER_RESPONSE" -or $obj.type -eq "MODEL") {
            foreach ($tc in $obj.tool_calls) {
                if ($tc.name -eq "write_to_file" -or $tc.name -eq "replace_file_content") {
                    $args = $tc.args
                    if ($args -eq $null) { continue }
                    
                    # Convert args from string if it is serialized as a string
                    if ($args -is [string]) {
                        $args = $args | ConvertFrom-Json
                    }
                    
                    $targetFile = $args.TargetFile
                    if ($targetFile -eq $null) {
                        $targetFile = $args.TargetFile
                    }
                    
                    if ($targetFile -ne $null) {
                        foreach ($tf in $targetFiles) {
                            if ($targetFile.EndsWith($tf)) {
                                $code = $args.CodeContent
                                if ($code -eq $null) {
                                    $code = $args.CodeContent
                                }
                                if ($code -ne $null) {
                                    $outDir = "c:\Users\JTBS-LIVE\Downloads\jtbs-live (1)\jtbs-live\scratch\extracted_backups"
                                    if (!(Test-Path $outDir)) {
                                        New-Item -ItemType Directory -Path $outDir -Force | Out-Null
                                    }
                                    $outFile = Join-Path $outDir "$($obj.step_index)_$tf"
                                    $code | Set-Content -Path $outFile -Force
                                    Write-Output "Extracted backup of $tf at step $($obj.step_index) to $outFile"
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch {
        # Ignore malformed JSON lines
    }
}
