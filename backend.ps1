[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('setup', 'check', 'start')]
    [string]$Action = 'start',
    [string]$ConfigDirectory,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Test-Java21([string]$JavaDirectory) {
    if ([string]::IsNullOrWhiteSpace($JavaDirectory)) { return $false }
    $releaseFile = Join-Path $JavaDirectory 'release'
    return ((Test-Path -LiteralPath (Join-Path $JavaDirectory 'bin/java.exe')) -and
        (Test-Path -LiteralPath (Join-Path $JavaDirectory 'bin/javac.exe')) -and
        (Test-Path -LiteralPath $releaseFile) -and
        ([System.IO.File]::ReadAllText($releaseFile) -match '(?m)^JAVA_VERSION="21(?:[.+_-]|")'))
}

function Find-Java21 {
    if (Test-Java21 $env:JAVA_HOME) { return $env:JAVA_HOME }
    $programFiles = [Environment]::GetFolderPath('ProgramFiles')
    foreach ($vendor in @('Eclipse Adoptium', 'Java', 'Microsoft')) {
        $vendorPath = Join-Path $programFiles $vendor
        if (-not (Test-Path -LiteralPath $vendorPath)) { continue }
        foreach ($candidate in (Get-ChildItem -LiteralPath $vendorPath -Directory)) {
            if (Test-Java21 $candidate.FullName) { return $candidate.FullName }
        }
    }
    return ''
}

function Read-Default([string]$Prompt, [string]$DefaultValue) {
    $enteredValue = Read-Host "$Prompt [$DefaultValue]"
    if ([string]::IsNullOrWhiteSpace($enteredValue)) { return $DefaultValue }
    return $enteredValue.Trim()
}

function Read-Port([string]$Prompt, [int]$DefaultValue) {
    $enteredPort = Read-Default $Prompt ([string]$DefaultValue)
    $parsedPort = 0
    if (-not [int]::TryParse($enteredPort, [ref]$parsedPort) -or $parsedPort -lt 1 -or $parsedPort -gt 65535) {
        throw 'Port must be a number between 1 and 65535.'
    }
    return $parsedPort
}

function Read-PasswordCredential([string]$UserName, [string]$Prompt) {
    if ([string]::IsNullOrWhiteSpace($UserName)) { throw 'A username is required.' }
    $securePassword = Read-Host $Prompt -AsSecureString
    if ($securePassword.Length -eq 0) { throw 'The password cannot be empty. Run setup again with the correct password.' }
    return [System.Management.Automation.PSCredential]::new($UserName, $securePassword)
}

function Set-WorkspaceJava21([string]$WorkspaceDirectory, [string]$JavaDirectory) {
    $vscodeDirectory = Join-Path $WorkspaceDirectory '.vscode'
    $settingsPath = Join-Path $vscodeDirectory 'settings.json'
    $editorSettings = [pscustomobject]@{}
    if (Test-Path -LiteralPath $settingsPath) {
        try { $editorSettings = Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json }
        catch { Write-Warning 'Existing VS Code settings could not be parsed; left unchanged. See LOCAL_SETUP.md for the Java settings.'; return }
    }
    $runtimes = @()
    if ($null -ne $editorSettings.PSObject.Properties['java.configuration.runtimes']) {
        foreach ($runtime in $editorSettings.'java.configuration.runtimes') {
            if ($runtime.name -eq 'JavaSE-21') { continue }
            $runtime | Add-Member -NotePropertyName 'default' -NotePropertyValue $false -Force
            $runtimes += $runtime
        }
    }
    $runtimes += [pscustomobject]@{ name = 'JavaSE-21'; path = $JavaDirectory; default = $true }
    $editorSettings | Add-Member -NotePropertyName 'java.configuration.runtimes' -NotePropertyValue $runtimes -Force
    $editorSettings | Add-Member -NotePropertyName 'java.configuration.updateBuildConfiguration' -NotePropertyValue 'automatic' -Force
    $editorSettings | Add-Member -NotePropertyName 'java.autobuild.enabled' -NotePropertyValue $false -Force
    New-Item -ItemType Directory -Path $vscodeDirectory -Force | Out-Null
    [System.IO.File]::WriteAllText($settingsPath, ($editorSettings | ConvertTo-Json -Depth 20), [System.Text.UTF8Encoding]::new($false))
}

try {
    if ([string]::IsNullOrWhiteSpace($ConfigDirectory)) {
        $localApplicationData = [Environment]::GetFolderPath('LocalApplicationData')
        if ([string]::IsNullOrWhiteSpace($localApplicationData)) { throw 'This launcher requires a Windows user profile.' }
        $ConfigDirectory = Join-Path $localApplicationData 'MAK/backend'
    }
    $ConfigDirectory = [System.IO.Path]::GetFullPath($ConfigDirectory)
    $configurationFile = Join-Path $ConfigDirectory 'settings.xml'
    $configuration = $null
    if (Test-Path -LiteralPath $configurationFile) {
        try { $configuration = Import-Clixml -LiteralPath $configurationFile }
        catch {
            if ($Action -ne 'setup') { throw 'Saved settings cannot be decrypted. Run .\backend.cmd setup using this Windows account on this computer.' }
            Write-Warning 'Saved settings could not be decrypted. Re-enter the settings to replace them for this Windows account.'
        }
    }

    if ($Action -eq 'setup') {
        Write-Host 'MAK local backend setup'
        Write-Host 'Use your working PostgreSQL password. Setup does not reset it or change the database.'
        Write-Host 'Passwords are hidden while typing and encrypted for this Windows account and computer.'
        $dbHost = Read-Default 'Database host' 'localhost'
        $dbPort = Read-Port 'Database port' 5432
        $dbName = Read-Default 'Database name' 'car_rental'
        $dbUser = Read-Default 'PostgreSQL username' 'postgres'
        $dbCredential = Read-PasswordCredential $dbUser 'PostgreSQL password'
        $jdkDefault = Find-Java21
        if ($null -ne $configuration -and (Test-Java21 $configuration.JavaHome)) { $jdkDefault = $configuration.JavaHome }
        $jdkDirectory = Read-Default 'Java 21 JDK directory' $jdkDefault
        if (-not (Test-Java21 $jdkDirectory)) { throw 'A Java 21 JDK with java.exe and javac.exe is required. Check the directory and run setup again.' }

        $mailEnabled = $false; $mailHost = ''; $mailPort = 465; $mailFrom = ''
        $mailCredential = $null; $mailSsl = $true; $mailStartTls = $false
        if ($null -ne $configuration) {
            $mailEnabled = $configuration.MailEnabled; $mailHost = $configuration.MailHost
            $mailPort = $configuration.MailPort; $mailFrom = $configuration.MailFrom
            $mailCredential = $configuration.MailCredential
            $mailSsl = $configuration.MailSsl; $mailStartTls = $configuration.MailStartTls
        }
        $configureMail = Read-Default 'Configure SMTP now? Y/N (N keeps existing saved email settings)' 'N'
        if ($configureMail -match '^(?i:y|yes)$') {
            $mailHost = Read-Default 'SMTP server hostname' $mailHost
            if ([string]::IsNullOrWhiteSpace($mailHost)) { throw 'SMTP hostname is required.' }
            $mailPort = Read-Port 'SMTP port' $mailPort
            $savedMailUser = ''
            if ($null -ne $mailCredential) { $savedMailUser = $mailCredential.UserName }
            $mailUser = Read-Default 'SMTP username (usually your email address)' $savedMailUser
            $mailCredential = Read-PasswordCredential $mailUser 'SMTP password or app password'
            $senderDefault = $mailUser
            if (-not [string]::IsNullOrWhiteSpace($mailFrom)) { $senderDefault = $mailFrom }
            $mailFrom = Read-Default 'Permitted sender EMAIL address' $senderDefault
            try { $senderAddress = [System.Net.Mail.MailAddress]::new($mailFrom) }
            catch { throw 'Sender must be an email address, not the SMTP server hostname.' }
            if ($senderAddress.Address -ne $mailFrom -or -not $mailFrom.Contains('@')) { throw 'Enter a plain sender email address, for example rentals@example.com.' }
            $sslDefault = 'N'; $startTlsDefault = 'Y'
            if ($mailPort -eq 465) { $sslDefault = 'Y'; $startTlsDefault = 'N' }
            $mailSsl = (Read-Default 'Implicit SSL? Y/N' $sslDefault) -match '^(?i:y|yes)$'
            $mailStartTls = (Read-Default 'STARTTLS? Y/N' $startTlsDefault) -match '^(?i:y|yes)$'
            if ($mailSsl -and $mailStartTls) { throw 'Choose either implicit SSL or STARTTLS, following your provider settings.' }
            $mailEnabled = $true
        }
        $newConfiguration = [pscustomobject]@{
            Version = 1
            DatabaseUrl = "jdbc:postgresql://${dbHost}:${dbPort}/${dbName}"
            DatabaseCredential = $dbCredential
            JavaHome = $jdkDirectory
            MailEnabled = $mailEnabled
            MailHost = $mailHost
            MailPort = $mailPort
            MailFrom = $mailFrom
            MailCredential = $mailCredential
            MailSsl = $mailSsl
            MailStartTls = $mailStartTls
        }
        New-Item -ItemType Directory -Path $ConfigDirectory -Force | Out-Null
        $temporaryFile = Join-Path $ConfigDirectory ('settings-' + [guid]::NewGuid().ToString('N') + '.tmp')
        try {
            $newConfiguration | Export-Clixml -LiteralPath $temporaryFile -Depth 6
            Move-Item -LiteralPath $temporaryFile -Destination $configurationFile -Force
        }
        finally { if (Test-Path -LiteralPath $temporaryFile) { Remove-Item -LiteralPath $temporaryFile } }
        Set-WorkspaceJava21 $PSScriptRoot $jdkDirectory
        $parentWorkspace = Split-Path -Parent $PSScriptRoot
        if ((Split-Path -Leaf $PSScriptRoot) -eq 'backend' -and (Test-Path -LiteralPath (Join-Path $parentWorkspace 'frontend'))) {
            Set-WorkspaceJava21 $parentWorkspace $jdkDirectory
        }
        Write-Host "Saved local settings: $configurationFile"
        Write-Host 'Database and SMTP connectivity have not been tested. No server was started and no email was sent.'
        Write-Host 'Reload your VS Code window once, then run .\backend.cmd start in any terminal.'
        exit 0
    }

    if ($null -eq $configuration) { throw 'Local settings are missing. Run .\backend.cmd setup first.' }
    if ($configuration.Version -ne 1) { throw 'Unsupported local settings version. Run .\backend.cmd setup again.' }
    if ($configuration.DatabaseCredential -isnot [System.Management.Automation.PSCredential] -or
        [string]::IsNullOrWhiteSpace($configuration.DatabaseCredential.UserName) -or
        $configuration.DatabaseCredential.Password.Length -eq 0) { throw 'Saved database credentials are incomplete. Run .\backend.cmd setup again.' }
    if ($configuration.DatabaseUrl -notmatch '^jdbc:postgresql://.+/.+$') { throw 'Saved database URL is invalid. Run .\backend.cmd setup again.' }
    if (-not (Test-Java21 $configuration.JavaHome)) { throw 'The saved Java 21 JDK is missing or incompatible. Run .\backend.cmd setup again.' }
    if ($configuration.MailEnabled -and ($configuration.MailCredential -isnot [System.Management.Automation.PSCredential] -or
        [string]::IsNullOrWhiteSpace($configuration.MailHost) -or [string]::IsNullOrWhiteSpace($configuration.MailFrom) -or
        $configuration.MailCredential.Password.Length -eq 0)) { throw 'Saved SMTP settings are incomplete. Run .\backend.cmd setup again.' }
    if ($Action -eq 'check') {
        Write-Host 'Saved settings decrypt successfully and Java 21 is available.'
        Write-Host 'This checks local configuration only; database and SMTP connectivity have not been tested.'
        exit 0
    }

    $mavenWrapper = Join-Path $PSScriptRoot 'mvnw.cmd'
    if (-not (Test-Path -LiteralPath $mavenWrapper)) { throw 'mvnw.cmd was not found beside backend.ps1.' }
    $launchVariables = @{
        JAVA_HOME = $configuration.JavaHome
        PATH = (Join-Path $configuration.JavaHome 'bin') + ';' + $env:PATH
        DB_URL = $configuration.DatabaseUrl
        DB_USER = $configuration.DatabaseCredential.UserName
        DB_PASSWORD = $configuration.DatabaseCredential.GetNetworkCredential().Password
        MAIL_ENABLED = ([bool]$configuration.MailEnabled).ToString().ToLowerInvariant()
        MAIL_HOST = ''
        MAIL_PORT = '587'
        MAIL_FROM = ''
        MAIL_USERNAME = ''
        MAIL_PASSWORD = ''
        MAIL_AUTH = 'true'
        MAIL_SSL = 'false'
        MAIL_STARTTLS = 'true'
    }
    if ($configuration.MailEnabled) {
        $launchVariables.MAIL_HOST = $configuration.MailHost
        $launchVariables.MAIL_PORT = [string]$configuration.MailPort
        $launchVariables.MAIL_FROM = $configuration.MailFrom
        $launchVariables.MAIL_USERNAME = $configuration.MailCredential.UserName
        $launchVariables.MAIL_PASSWORD = $configuration.MailCredential.GetNetworkCredential().Password
        $launchVariables.MAIL_AUTH = 'true'
        $launchVariables.MAIL_SSL = ([bool]$configuration.MailSsl).ToString().ToLowerInvariant()
        $launchVariables.MAIL_STARTTLS = ([bool]$configuration.MailStartTls).ToString().ToLowerInvariant()
    }
    $previousVariables = @{}
    foreach ($variableName in $launchVariables.Keys) {
        $previousVariables[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process')
        [Environment]::SetEnvironmentVariable($variableName, $launchVariables[$variableName], 'Process')
    }
    Write-Host 'Starting MAK with saved local settings and a clean Java 21 build. Press Ctrl+C to stop.'
    $backendExitCode = 1
    Push-Location $PSScriptRoot
    try {
        $mavenArguments = @('clean', 'spring-boot:run')
        if ($Offline) { $mavenArguments = @('-o') + $mavenArguments }
        & $mavenWrapper @mavenArguments
        $backendExitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
        foreach ($variableName in $previousVariables.Keys) { [Environment]::SetEnvironmentVariable($variableName, $previousVariables[$variableName], 'Process') }
        $launchVariables.Clear()
    }
    exit $backendExitCode
}
catch {
    Write-Host ('MAK startup: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
