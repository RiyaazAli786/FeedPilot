# Shared helpers for the bridge.reelsflow.io deployment scripts (dot-sourced).
$ErrorActionPreference = "Stop"
$script:DeployDir = $PSScriptRoot
$script:RepoRoot  = Split-Path -Parent $PSScriptRoot

function Read-EnvFile([string]$Path) {
    $map = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $i = $line.IndexOf('=')
        $map[$line.Substring(0, $i).Trim()] = $line.Substring($i + 1).Trim()
    }
    return $map
}

function Get-DeployConfig {
    $cfg = Read-EnvFile (Join-Path $script:DeployDir "config.env")
    $secretsPath = Join-Path $script:DeployDir ".deploy-secrets.env"
    $pw = $env:SSH_PASSWORD
    if ([string]::IsNullOrWhiteSpace($pw) -and (Test-Path $secretsPath)) {
        $pw = (Read-EnvFile $secretsPath)["SSH_PASSWORD"]
    }
    if ([string]::IsNullOrWhiteSpace($pw)) {
        $secure = Read-Host "SSH password for $($cfg.SSH_USER)@$($cfg.SERVER_IP)" -AsSecureString
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $pw = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    }
    $cfg["SSH_PASSWORD"] = $pw
    return $cfg
}

function Find-PuttyTool([string]$Name) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $p = Join-Path ${env:ProgramFiles} "PuTTY\$Name.exe"
    if (Test-Path $p) { return $p }
    throw "$Name not found. Install PuTTY (winget install PuTTY.PuTTY)."
}

function Invoke-Remote($cfg, [string]$Command) {
    $plink = Find-PuttyTool "plink"
    & $plink -ssh -batch -P $cfg.SSH_PORT -hostkey $cfg.SSH_HOSTKEY -pw $cfg.SSH_PASSWORD "$($cfg.SSH_USER)@$($cfg.SERVER_IP)" $Command
    if ($LASTEXITCODE -ne 0) { throw "Remote command failed (exit $LASTEXITCODE): $Command" }
}

function Copy-ToRemote($cfg, [string[]]$LocalPaths, [string]$RemoteDir) {
    $pscp = Find-PuttyTool "pscp"
    & $pscp -batch -P $cfg.SSH_PORT -hostkey $cfg.SSH_HOSTKEY -pw $cfg.SSH_PASSWORD @LocalPaths "$($cfg.SSH_USER)@$($cfg.SERVER_IP):$RemoteDir/"
    if ($LASTEXITCODE -ne 0) { throw "Upload to $RemoteDir failed (exit $LASTEXITCODE)." }
}
