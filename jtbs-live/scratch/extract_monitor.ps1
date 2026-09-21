$logPath = "C:\Users\JTBS-LIVE\.gemini\antigravity\brain\157d68a2-5496-41de-b309-2a8c53de405a\.system_generated\logs\transcript_full.jsonl"
$outFile = "c:\Users\JTBS-LIVE\Downloads\jtbs-live (1)\jtbs-live\scratch\10869_RecoveryManager.json"

Get-Content $logPath | ForEach-Object {
    try {
        $obj = $_ | ConvertFrom-Json
        if ($obj.step_index -ge 10868 -and $obj.step_index -le 10874) {
            $_ | Add-Content -Path $outFile
        }
    } catch {
        # Ignore
    }
}
