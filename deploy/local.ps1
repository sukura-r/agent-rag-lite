# Run from the repository root: ./deploy/local.ps1
$ErrorActionPreference = 'Stop'
$agentRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $agentRoot
if (Test-Path -LiteralPath '.env') {
    foreach ($agentLine in Get-Content -LiteralPath '.env' -Encoding utf8) {
        $agentLine = $agentLine.Trim()
        if (!$agentLine -or $agentLine.StartsWith('#')) { continue }
        if ($agentLine -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { throw "Invalid .env entry" }
        $agentKey = $Matches[1]
        $agentValue = $Matches[2].Trim()
        if ($agentValue.Length -ge 2 -and (($agentValue.StartsWith('"') -and $agentValue.EndsWith('"')) -or ($agentValue.StartsWith("'") -and $agentValue.EndsWith("'")))) {
            $agentValue = $agentValue.Substring(1, $agentValue.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($agentKey, $agentValue, 'Process')
    }
}
$agentJar = Join-Path $agentRoot 'target/agent-rag-lite-0.0.1-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $agentJar)) { throw 'Build first: npm ci, npm run build (frontend), then mvn package (root).' }
& java '-Xms256m' '-Xmx1024m' '-jar' $agentJar
exit $LASTEXITCODE
