$prevSessionLog = "C:\Users\JTBS-LIVE\.gemini\antigravity\brain\882760fc-f790-4317-93ee-91f4c6c22ac8\.system_generated\logs\transcript_full.jsonl"
$outDir = "c:\Users\JTBS-LIVE\Downloads\jtbs-live (1)\jtbs-live\scratch\extracted_exoplayer"

if (!(Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}

# Read previous session transcript to extract CustomLoadControl.kt, PlayerFactory.kt, RecoveryManager.kt, PlayerHealthMonitor.kt
Get-Content $prevSessionLog | ForEach-Object {
    try {
        $obj = $_ | ConvertFrom-Json
        if ($obj.type -eq "PLANNER_RESPONSE" -or $obj.type -eq "MODEL") {
            foreach ($tc in $obj.tool_calls) {
                if ($tc.name -eq "write_to_file") {
                    $args = $tc.args
                    if ($args -is [string]) { $args = $args | ConvertFrom-Json }
                    
                    $target = $args.TargetFile
                    $code = $args.CodeContent
                    if ($target -ne $null -and $code -ne $null) {
                        if ($target.EndsWith("CustomLoadControl.kt") -and $obj.step_index -eq 5303) {
                            $code | Set-Content -Path (Join-Path $outDir "CustomLoadControl.kt") -Force
                            Write-Output "Extracted CustomLoadControl.kt"
                        }
                        if ($target.EndsWith("PlayerFactory.kt") -and $obj.step_index -eq 5305) {
                            $code | Set-Content -Path (Join-Path $outDir "PlayerFactory.kt") -Force
                            Write-Output "Extracted PlayerFactory.kt"
                        }
                        if ($target.EndsWith("RecoveryManager.kt") -and $obj.step_index -eq 5307) {
                            $code | Set-Content -Path (Join-Path $outDir "RecoveryManager.kt") -Force
                            Write-Output "Extracted RecoveryManager.kt"
                        }
                        if ($target.EndsWith("PlayerHealthMonitor.kt") -and $obj.step_index -eq 5309) {
                            $code | Set-Content -Path (Join-Path $outDir "PlayerHealthMonitor.kt") -Force
                            Write-Output "Extracted PlayerHealthMonitor.kt"
                        }
                    }
                }
            }
        }
    } catch {
        # Ignore
    }
}
