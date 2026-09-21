$logPath = "C:\Users\JTBS-LIVE\.gemini\antigravity\brain\157d68a2-5496-41de-b309-2a8c53de405a\.system_generated\logs\transcript_full.jsonl"
$targetFiles = @(
    "MainActivity.kt",
    "PlayerHealthMonitor.kt",
    "RecoveryManager.kt"
)

Get-Content $logPath | ForEach-Object {
    try {
        $obj = $_ | ConvertFrom-Json
        if ($obj.step_index -ge 11209) { return }
        
        if ($obj.type -eq "PLANNER_RESPONSE" -or $obj.type -eq "MODEL") {
            foreach ($tc in $obj.tool_calls) {
                if ($tc.name -eq "replace_file_content" -or $tc.name -eq "multi_replace_file_content") {
                    $args = $tc.args
                    if ($args -is [string]) {
                        $args = $args | ConvertFrom-Json
                    }
                    $targetFile = $args.TargetFile
                    
                    $matched = $false
                    foreach ($tf in $targetFiles) {
                        if ($targetFile.EndsWith($tf)) { $matched = $true }
                    }
                    
                    if ($matched) {
                        Write-Output "=== Step $($obj.step_index) : $targetFile ==="
                        if ($tc.name -eq "replace_file_content") {
                            Write-Output "TARGET:"
                            Write-Output $args.TargetContent
                            Write-Output "REPLACEMENT:"
                            Write-Output $args.ReplacementContent
                        } else {
                            # multi_replace_file_content
                            foreach ($chunk in $args.ReplacementChunks) {
                                Write-Output "CHUNK TARGET:"
                                Write-Output $chunk.TargetContent
                                Write-Output "CHUNK REPLACEMENT:"
                                Write-Output $chunk.ReplacementContent
                            }
                        }
                        Write-Output "========================================"
                    }
                }
            }
        }
    } catch {
        # Ignore
    }
}
