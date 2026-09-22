param(
    [string]$ServerAddr = "127.0.0.1:8848",
    [string]$Namespace = "",
    [string]$Group = "DEFAULT_GROUP",
    [string]$Username = "",
    [string]$Password = ""
)

$baseUrl = "http://$ServerAddr"
$headers = @{}

if ($Username) {
    $login = Invoke-RestMethod -Method Post -Uri "$baseUrl/nacos/v1/auth/login" -Body @{
        username = $Username
        password = $Password
    }
    if (-not $login.accessToken) {
        throw "Nacos login failed: access token was not returned."
    }
    $headers['accessToken'] = $login.accessToken
}

$ruleFiles = Get-ChildItem (Join-Path $PSScriptRoot 'sentinel-rules') -Filter '*.json'
if (-not $ruleFiles) {
    throw 'No Sentinel rule files were found.'
}

foreach ($ruleFile in $ruleFiles) {
    $body = @{
        dataId = $ruleFile.Name
        group = $Group
        content = Get-Content $ruleFile.FullName -Raw
        type = 'json'
    }
    if ($Namespace) { $body.tenant = $Namespace }
    $result = Invoke-RestMethod -Method Post -Uri "$baseUrl/nacos/v1/cs/configs" -Headers $headers -Body $body
    if ($result -ne $true -and $result -ne 'true') {
        throw "Nacos rejected $($ruleFile.Name): $result"
    }
    Write-Host "Published $($ruleFile.Name)"
}
