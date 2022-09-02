while ($true) {
    $os = Get-WmiObject win32_operatingsystem -ComputerName localhost -ErrorAction SilentlyContinue
    $uptime = ((get-date) - ($os.ConvertToDateTime($os.lastbootuptime)))
    $totalUptime = ($uptime.Days * 1440) + ($uptime.Hours * 60) + ($uptime.Minutes)
    Write-Output Uptime: $totalUptime

    if ($totalUptime -gt 50) {
        Stop-Computer -ComputerName localhost
    };

    Start-Sleep 30;
}
