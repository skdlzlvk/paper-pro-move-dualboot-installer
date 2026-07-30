[CmdletBinding()]
param(
    [Parameter()]
    [ValidateNotNullOrEmpty()]
    [string]$DeviceAddress = '10.11.99.1',

    [Parameter()]
    [ValidateRange(1, 65535)]
    [int]$SshPort = 22,

    [Parameter()]
    [ValidateRange(1, 65535)]
    [int]$AdbPort = 5555,

    [Parameter()]
    [switch]$Json
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$results = [System.Collections.Generic.List[object]]::new()

function Add-Check {
    param(
        [Parameter(Mandatory)]
        [string]$Name,

        [Parameter(Mandatory)]
        [bool]$Passed,

        [Parameter(Mandatory)]
        [bool]$Required,

        [Parameter(Mandatory)]
        [string]$Detail
    )

    $results.Add([pscustomobject]@{
        Name = $Name
        Passed = $Passed
        Required = $Required
        Detail = $Detail
    })
}

function Test-TcpPort {
    param(
        [Parameter(Mandatory)]
        [string]$Address,

        [Parameter(Mandatory)]
        [int]$Port,

        [Parameter()]
        [int]$TimeoutMilliseconds = 1200
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $task = $client.ConnectAsync($Address, $Port)
        if (-not $task.Wait($TimeoutMilliseconds)) {
            return $false
        }
        return $client.Connected
    }
    catch {
        return $false
    }
    finally {
        $client.Dispose()
    }
}

$runningOnWindows = $env:OS -eq 'Windows_NT'
Add-Check -Name 'Windows host' -Passed $runningOnWindows -Required $true `
    -Detail $(if ($runningOnWindows) { 'Windows host detected.' } else {
        'The planned installer supports Windows only.'
    })

$powerShellVersionSupported = $PSVersionTable.PSVersion.Major -ge 5
Add-Check -Name 'PowerShell 5+' -Passed $powerShellVersionSupported `
    -Required $true -Detail "PowerShell $($PSVersionTable.PSVersion)"

foreach ($commandName in @('ssh', 'scp')) {
    $command = Get-Command $commandName -ErrorAction SilentlyContinue
    Add-Check -Name "$commandName available" -Passed ($null -ne $command) `
        -Required $true -Detail $(if ($null -ne $command) {
            $command.Source
        } else {
            "$commandName was not found in PATH."
        })
}

$adb = Get-Command adb -ErrorAction SilentlyContinue
Add-Check -Name 'adb available' -Passed ($null -ne $adb) -Required $false `
    -Detail $(if ($null -ne $adb) {
        $adb.Source
    } else {
        'Optional for the current preflight; required by later Android diagnostics.'
    })

$sshReachable = Test-TcpPort -Address $DeviceAddress -Port $SshPort
Add-Check -Name 'Device SSH reachable' -Passed $sshReachable -Required $false `
    -Detail "$DeviceAddress`:$SshPort (no authentication attempted)"

$adbReachable = Test-TcpPort -Address $DeviceAddress -Port $AdbPort
Add-Check -Name 'Device ADB reachable' -Passed $adbReachable -Required $false `
    -Detail "$DeviceAddress`:$AdbPort (no ADB connection attempted)"

$requiredFailures = @($results | Where-Object {
    $_.Required -and -not $_.Passed
})

$summary = [pscustomobject]@{
    Tool = 'Paper Pro Move dual-boot preflight'
    Version = '0.1.0'
    Mode = 'read-only'
    DeviceAddress = $DeviceAddress
    Passed = $requiredFailures.Count -eq 0
    Checks = $results
    Notice = 'No device authentication, upload, boot change, or partition write was performed.'
}

if ($Json) {
    $summary | ConvertTo-Json -Depth 5
}
else {
    $results | Format-Table Name, Passed, Required, Detail -AutoSize
    Write-Host ''
    Write-Host $summary.Notice
}

if ($requiredFailures.Count -gt 0) {
    exit 1
}

exit 0
