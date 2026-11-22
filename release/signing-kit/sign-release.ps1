#requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Manifest values describe data only. They never become commands or tool paths.
$script:KitRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$script:PasswordEnvironmentName = 'DIARY_OWNER_SIGN_PASSWORD'
$script:JavaPath = $null
$script:KeytoolPath = $null
$password = $null
$confirmation = $null
$passwordPointer = [IntPtr]::Zero
$confirmationPointer = [IntPtr]::Zero
$temporaryKey = $null
$temporaryCertificate = $null
$temporaryApk = $null
$temporaryFingerprint = $null
$temporaryChecksum = $null
$completed = $false

function Assert-NoReparsePoint {
    param([Parameter(Mandatory = $true)][string]$Path)
    $current = [IO.Path]::GetFullPath($Path)
    while ($current) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Ссылка или junction запрещены для файлов подписи: $current"
            }
        }
        $parent = [IO.Path]::GetDirectoryName($current.TrimEnd([char[]]'\/'))
        if (-not $parent -or $parent -eq $current) { break }
        $current = $parent
    }
}

function Test-InDirectory {
    param([string]$Path, [string]$Directory)
    $directoryPath = [IO.Path]::GetFullPath($Directory).TrimEnd([char[]]'\/')
    $candidate = [IO.Path]::GetFullPath($Path)
    return ($candidate.Equals($directoryPath, [StringComparison]::OrdinalIgnoreCase) -or
        $candidate.StartsWith($directoryPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase))
}

function Assert-RegularFile {
    param([string]$Path)
    Assert-NoReparsePoint $Path
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer) { throw "Ожидался обычный файл: $Path" }
}

function Assert-PrivateAcl {
    param([string]$Path)
    # Existing directories may be shared: inspect them instead of changing their
    # permissions. A copied key must not retain an explicit Users/Everyone ACE.
    $allowedSids = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,
        'S-1-5-18', 'S-1-5-32-544')
    $acl = Get-Acl -LiteralPath $Path
    $ownerSid = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
    if ($allowedSids -notcontains $ownerSid) {
        throw "У папки или ключа другой владелец: $Path. Используйте новую личную локальную папку и восстановите туда свою копию ключа. Общие разрешения скрипт не меняет."
    }
    foreach ($rule in $acl.Access) {
        if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow) {
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            if ($allowedSids -notcontains $sid) {
                throw "Слишком широкий доступ к папке или ключу: $Path. Допускаются только ваш пользователь, SYSTEM и администраторы. Используйте новую личную локальную папку; общие разрешения скрипт не меняет."
            }
        }
    }
}

function Get-KitFile {
    param([string]$RelativePath)
    # Call sites supply constants; manifest file names are checked separately.
    $path = [IO.Path]::GetFullPath((Join-Path $script:KitRoot $RelativePath))
    if (-not (Test-InDirectory $path $script:KitRoot)) { throw 'Файл находится вне комплекта.' }
    Assert-RegularFile $path
    return $path
}

function Assert-Hash {
    param([string]$Path, [string]$Expected)
    Assert-RegularFile $Path
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if (-not $actual.Equals($Expected, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Контрольная сумма SHA-256 не совпала: $([IO.Path]::GetFileName($Path)). Распакуйте исходный комплект заново."
    }
}

function Invoke-NativeChecked {
    param([string]$Executable, [string[]]$Arguments, [string]$Description)
    # Windows PowerShell 5.1 treats native stderr as ErrorRecord. Capture both
    # streams with Continue, then use the process exit code as the result.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $nativeOutput = @(& $Executable @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $nativeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    $outputText = $nativeOutput -join [Environment]::NewLine
    if ($nativeExitCode -ne 0) {
        throw "$Description — код ошибки $nativeExitCode.`n$outputText"
    }
    return $outputText
}

function Find-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += (Join-Path $env:JAVA_HOME 'bin')
    }
    $javaCommands = @(Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue)
    foreach ($command in $javaCommands) {
        $candidates += [IO.Path]::GetDirectoryName($command.Source)
    }
    foreach ($bin in ($candidates | Select-Object -Unique)) {
        $java = Join-Path $bin 'java.exe'
        $keytool = Join-Path $bin 'keytool.exe'
        if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
            -not (Test-Path -LiteralPath $keytool -PathType Leaf)) { continue }
        try {
            $versionOutput = Invoke-NativeChecked $java @('-version') 'Проверка Java'
            if ($versionOutput -match '(?im)(?:openjdk|java)(?:\s+version)?\s+"?(\d+)(?:[.\s"+-]|$)' -and [int]$Matches[1] -ge 17) {
                $script:JavaPath = $java
                $script:KeytoolPath = $keytool
                return
            }
        } catch { continue }
    }
    throw "Нужен JDK 17 или новее с java и keytool. Установите официальный Eclipse Temurin 21 JDK для Windows, затем снова откройте Sign-Windows.cmd:`nhttps://adoptium.net/temurin/releases/?version=21&os=windows&package=jdk`nПри установке включите Add to PATH и JAVA_HOME. Комплект ничего не устанавливает автоматически."
}

function Invoke-ApkSigner {
    param([string[]]$Arguments, [string]$Description)
    Assert-Hash $script:ApkSignerPath $script:ApkSignerHash
    return Invoke-NativeChecked $script:JavaPath (@('-jar', $script:ApkSignerPath) + $Arguments) $Description
}

function Get-CertificateFingerprint {
    param([string]$CertificatePath)
    $certificate = New-Object -TypeName System.Security.Cryptography.X509Certificates.X509Certificate2 -ArgumentList $CertificatePath
    try {
        if ($certificate.Subject -match '(?i)android[\s_-]*debug') {
            throw 'Обнаружен сертификат Android Debug. Нужен отдельный релизный ключ владельца.'
        }
        $algorithm = [Security.Cryptography.SHA256]::Create()
        try { return ([BitConverter]::ToString($algorithm.ComputeHash($certificate.RawData))).Replace('-', '') }
        finally { $algorithm.Dispose() }
    } finally { $certificate.Dispose() }
}

try {
    [Environment]::SetEnvironmentVariable($script:PasswordEnvironmentName, $null, 'Process')
    if ($env:OS -ne 'Windows_NT') { throw 'Для этой команды нужен Windows. В Linux/macOS используйте sign-release.sh.' }
    Assert-NoReparsePoint $script:KitRoot
    $manifestPath = Get-KitFile 'release-manifest.json'
    if ((Get-Item -LiteralPath $manifestPath).Length -gt 32768) { throw 'Слишком большой release-manifest.json.' }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $fields = @('schemaVersion', 'applicationId', 'versionName', 'versionCode', 'minSdk',
        'unsignedApkFile', 'unsignedApkSha256', 'apksignerJarFile', 'apksignerJarSha256')
    $optionalFields = @('androidSdkNoticeFile', 'androidSdkNoticeSha256', 'artifactIntent',
        'unsignedCandidate', 'productionSigned', 'submissionReady', 'publishedToStore')
    $actualFields = @($manifest.PSObject.Properties.Name)
    foreach ($field in $fields) {
        if (-not ($actualFields -ccontains $field)) { throw "В release-manifest.json отсутствует поле $field." }
    }
    foreach ($field in $actualFields) {
        if (-not (($fields + $optionalFields) -ccontains $field)) { throw "Неизвестное поле release-manifest.json: $field." }
    }
    foreach ($numericField in @('schemaVersion', 'versionCode', 'minSdk')) {
        if (($manifest.$numericField -isnot [int]) -and ($manifest.$numericField -isnot [long])) {
            throw "Поле $numericField должно быть целым числом."
        }
    }
    foreach ($stringField in @('applicationId', 'versionName', 'unsignedApkFile', 'apksignerJarFile')) {
        if ($manifest.$stringField -isnot [string]) { throw "Поле $stringField должно быть строкой." }
    }
    if ($manifest.schemaVersion -ne 1 -or $manifest.applicationId -cne 'ru.privatediary' -or
        $manifest.versionName -cne '1.0.0' -or $manifest.versionCode -ne 11 -or $manifest.minSdk -ne 26 -or
        $manifest.unsignedApkFile -cne 'Private-Diary-1.0.0-store-release-unsigned.apk' -or
        $manifest.apksignerJarFile -cne 'tools/apksigner.jar') {
        throw 'Этот комплект предназначен только для ru.privatediary, 1.0.0 (11), minSdk 26. Имена файлов должны совпадать с исходным комплектом.'
    }
    foreach ($hashField in @('unsignedApkSha256', 'apksignerJarSha256')) {
        if ($manifest.$hashField -isnot [string] -or $manifest.$hashField -cnotmatch '\A[0-9a-fA-F]{64}\z') {
            throw "Неверный SHA-256 в поле $hashField."
        }
    }
    if (($actualFields -ccontains 'androidSdkNoticeFile') -ne ($actualFields -ccontains 'androidSdkNoticeSha256')) {
        throw 'В манифесте должны быть оба поля NOTICE: имя файла и SHA-256.'
    }
    if ($actualFields -ccontains 'androidSdkNoticeFile') {
        if ($manifest.androidSdkNoticeFile -isnot [string] -or $manifest.androidSdkNoticeFile -cne 'tools/NOTICE-Android-SDK.txt' -or
            $manifest.androidSdkNoticeSha256 -isnot [string] -or $manifest.androidSdkNoticeSha256 -cnotmatch '\A[0-9a-fA-F]{64}\z') {
            throw 'Неверные поля NOTICE в манифесте.'
        }
        Assert-Hash (Get-KitFile 'tools/NOTICE-Android-SDK.txt') $manifest.androidSdkNoticeSha256
    }
    if ($actualFields -ccontains 'artifactIntent') {
        if ($manifest.artifactIntent -isnot [string] -or $manifest.artifactIntent -cne 'unsigned_store_release_for_owner_signing') { throw 'Неверное назначение комплекта в манифесте.' }
    }
    foreach ($flag in @('unsignedCandidate', 'productionSigned', 'submissionReady', 'publishedToStore')) {
        if ($actualFields -ccontains $flag) {
            $expectedFlag = $flag -ceq 'unsignedCandidate'
            if ($manifest.$flag -isnot [bool] -or $manifest.$flag -ne $expectedFlag) { throw "Неверное значение $flag в манифесте." }
        }
    }
    $unsignedApk = Get-KitFile 'Private-Diary-1.0.0-store-release-unsigned.apk'
    $script:ApkSignerPath = Get-KitFile 'tools/apksigner.jar'
    $script:ApkSignerHash = $manifest.apksignerJarSha256
    Assert-Hash $unsignedApk $manifest.unsignedApkSha256
    Assert-Hash $script:ApkSignerPath $script:ApkSignerHash
    Find-Jdk

    $keyDirectoryInput = $env:DIARY_OWNER_KEY_DIRECTORY
    if (-not $keyDirectoryInput) {
        if (-not $env:USERPROFILE) { throw 'Не задан USERPROFILE. Не удалось найти личную папку владельца.' }
        $keyDirectoryInput = Join-Path $env:USERPROFILE '.private-diary-signing'
    }
    if ($keyDirectoryInput -notmatch '\A[A-Za-z]:[\\/]') {
        throw 'Папка ключа должна быть абсолютным локальным путём Windows (например C:\Users\Name\.private-diary-signing). Сетевые пути не допускаются.'
    }
    $keyDirectory = [IO.Path]::GetFullPath($keyDirectoryInput).TrimEnd([char[]]'\/')
    if ($keyDirectory.Length -le 2) { throw 'Не сохраняйте ключ в корне диска. Выберите отдельную личную папку.' }
    if (Test-InDirectory $keyDirectory $script:KitRoot) { throw 'Ключ нельзя сохранять внутри комплекта подписи.' }
    Assert-NoReparsePoint $keyDirectory
    if (Test-Path -LiteralPath $keyDirectory) {
        if (-not (Test-Path -LiteralPath $keyDirectory -PathType Container)) { throw 'Путь ключа должен быть папкой.' }
        Assert-PrivateAcl $keyDirectory
    }
    $ancestor = $keyDirectory
    while ($ancestor) {
        if (Test-Path -LiteralPath (Join-Path $ancestor '.git')) { throw 'Ключ нельзя сохранять в Git-репозитории. Выберите личную локальную папку вне исходного кода.' }
        $parent = [IO.Path]::GetDirectoryName($ancestor)
        if (-not $parent -or $parent -eq $ancestor) { break }
        $ancestor = $parent
    }
    foreach ($cloudRoot in @($env:OneDrive, $env:OneDriveConsumer, $env:OneDriveCommercial)) {
        if ($cloudRoot -and (Test-InDirectory $keyDirectory $cloudRoot)) { throw 'Папка ключа находится в OneDrive. Выберите личную локальную папку без синхронизации.' }
    }
    $keystore = Join-Path $keyDirectory 'private-diary-release.p12'
    $fingerprintFile = Join-Path $keyDirectory 'publickeyfingerprint.txt'
    Assert-NoReparsePoint $keystore
    Assert-NoReparsePoint $fingerprintFile
    $keyExists = Test-Path -LiteralPath $keystore
    $recordExists = Test-Path -LiteralPath $fingerprintFile
    if ($recordExists -and -not $keyExists) {
        throw "Сохранён отпечаток прежнего ключа, но сам ключ потерян. Новый ключ не создаётся. Восстановите private-diary-release.p12 из своей резервной копии в $keyDirectory."
    }
    if ($keyExists) {
        Assert-RegularFile $keystore
        Assert-PrivateAcl $keystore
    }
    $recordedFingerprint = $null
    if ($recordExists) {
        Assert-RegularFile $fingerprintFile
        if ((Get-Item -LiteralPath $fingerprintFile).Length -gt 4096) { throw 'Неверный файл отпечатка ключа.' }
        $recordLines = @((Get-Content -LiteralPath $fingerprintFile -Encoding UTF8) | Where-Object { $_ -ne '' })
        if ($recordLines.Count -ne 3 -or -not ($recordLines -ccontains 'applicationId=ru.privatediary') -or
            -not ($recordLines -ccontains 'alias=private-diary-release')) { throw 'Неверный файл отпечатка ключа. Восстановите его из резервной копии.' }
        $hashLines = @($recordLines | Where-Object { $_ -cmatch '\Asha256=[0-9a-fA-F]{64}\z' })
        if ($hashLines.Count -ne 1) { throw 'Неверный SHA-256 в файле отпечатка ключа.' }
        $recordedFingerprint = $hashLines[0].Substring(7).ToUpperInvariant()
    }

    $outputDirectory = Join-Path $script:KitRoot 'output'
    Assert-NoReparsePoint $outputDirectory
    if ((Test-Path -LiteralPath $outputDirectory) -and -not (Test-Path -LiteralPath $outputDirectory -PathType Container)) { throw 'output должна быть папкой.' }
    $signedApk = Join-Path $outputDirectory 'Private-Diary-1.0.0-RuStore.apk'
    $checksumFile = $signedApk + '.sha256'
    Assert-NoReparsePoint $signedApk
    Assert-NoReparsePoint $checksumFile
    if ((Test-Path -LiteralPath $signedApk) -or (Test-Path -LiteralPath $checksumFile)) {
        throw 'В output уже есть готовый APK или его SHA-256. Переместите прежние файлы в другую папку, затем запустите подпись снова. Существующий ключ будет использован повторно.'
    }

    Write-Host ''
    Write-Host 'Private Diary 1.0.0 (11): исходный APK и apksigner проверены по SHA-256.'
    Write-Host 'Пароль вводите здесь, в скрытом запросе. Не присылайте его в чат.'
    if ($keyExists) {
        Write-Host "Используется ваш существующий ключ: $keystore"
        $password = Read-Host 'Пароль существующего ключа' -AsSecureString
    } else {
        Write-Host "Будет создан личный ключ: $keystore"
        Write-Host 'Придумайте пароль от 12 символов: латинские буквы, цифры, знаки ASCII.'
        $password = Read-Host 'Новый пароль ключа' -AsSecureString
    }
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
    if ($password.Length -lt 12) { throw 'Нужен пароль не короче 12 символов.' }
    for ($index = 0; $index -lt $password.Length; $index++) {
        $character = [Runtime.InteropServices.Marshal]::ReadInt16($passwordPointer, $index * 2)
        if ($character -lt 32 -or $character -gt 126) { throw 'Пароль должен содержать только печатные ASCII: латинские буквы, цифры, знаки и пробел.' }
    }
    if (-not $keyExists) {
        $confirmation = Read-Host 'Повторите новый пароль с тем же регистром букв' -AsSecureString
        $confirmationPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($confirmation)
        $samePassword = $password.Length -eq $confirmation.Length
        for ($index = 0; $index -lt [Math]::Min($password.Length, $confirmation.Length); $index++) {
            if ([Runtime.InteropServices.Marshal]::ReadInt16($passwordPointer, $index * 2) -ne
                [Runtime.InteropServices.Marshal]::ReadInt16($confirmationPointer, $index * 2)) { $samePassword = $false }
        }
        if (-not $samePassword) { throw 'Пароли не совпали. Учитывается регистр букв. Ключ не создан.' }
    }
    [Environment]::SetEnvironmentVariable($script:PasswordEnvironmentName,
        [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer), 'Process')
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    $passwordPointer = [IntPtr]::Zero
    if ($confirmationPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($confirmationPointer)
        $confirmationPointer = [IntPtr]::Zero
    }
    $password.Dispose()
    $password = $null
    if ($confirmation) { $confirmation.Dispose(); $confirmation = $null }

    if (-not (Test-Path -LiteralPath $keyDirectory)) {
        [void][IO.Directory]::CreateDirectory($keyDirectory)
        # Newly created key storage is readable only by this user and SYSTEM.
        $acl = New-Object System.Security.AccessControl.DirectorySecurity
        $acl.SetAccessRuleProtection($true, $false)
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl.SetOwner($identity)
        $systemIdentity = New-Object -TypeName System.Security.Principal.SecurityIdentifier -ArgumentList 'S-1-5-18'
        foreach ($principal in @($identity, $systemIdentity)) {
            $rule = New-Object -TypeName System.Security.AccessControl.FileSystemAccessRule -ArgumentList @($principal, 'FullControl', 'ContainerInherit, ObjectInherit', 'None', 'Allow')
            [void]$acl.AddAccessRule($rule)
        }
        Set-Acl -LiteralPath $keyDirectory -AclObject $acl
    }
    Assert-NoReparsePoint $keyDirectory
    Assert-PrivateAcl $keyDirectory
    $keytoolEnvironment = @('-storetype', 'PKCS12', '-storepass:env', $script:PasswordEnvironmentName)
    $signingKeystore = $keystore
    if (-not $keyExists) {
        $temporaryKey = Join-Path $keyDirectory ('.new-key-' + [Guid]::NewGuid().ToString('N') + '.p12')
        Write-Host 'Создаётся ваш релизный ключ RSA 3072…'
        $null = Invoke-NativeChecked $script:KeytoolPath (@('-genkeypair', '-keystore', $temporaryKey,
            '-alias', 'private-diary-release', '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA',
            '-validity', '10000', '-dname', 'CN=Private Diary', '-keypass:env', $script:PasswordEnvironmentName) + $keytoolEnvironment) 'Создание ключа'
        Assert-PrivateAcl $temporaryKey
        $signingKeystore = $temporaryKey
    }
    $temporaryCertificate = Join-Path $keyDirectory ('.certificate-' + [Guid]::NewGuid().ToString('N') + '.der')
    $null = Invoke-NativeChecked $script:KeytoolPath (@('-exportcert', '-keystore', $signingKeystore,
        '-alias', 'private-diary-release', '-file', $temporaryCertificate) + $keytoolEnvironment) 'Проверка сертификата ключа'
    $fingerprint = Get-CertificateFingerprint $temporaryCertificate
    if ($recordedFingerprint -and $recordedFingerprint -cne $fingerprint) {
        throw 'Сертификат ключа не совпал с сохранённым SHA-256. Подпись остановлена. Восстановите правильный ключ из резервной копии.'
    }
    if (-not $keyExists) {
        Assert-NoReparsePoint $keystore
        # File.Move refuses to overwrite even if another process created the key.
        [IO.File]::Move($temporaryKey, $keystore)
        $temporaryKey = $null
    }
    if (-not $recordExists) {
        $temporaryFingerprint = Join-Path $keyDirectory ('.fingerprint-' + [Guid]::NewGuid().ToString('N') + '.txt')
        $record = "applicationId=ru.privatediary`nalias=private-diary-release`nsha256=$fingerprint`n"
        [IO.File]::WriteAllText($temporaryFingerprint, $record, [Text.Encoding]::ASCII)
        [IO.File]::Move($temporaryFingerprint, $fingerprintFile)
        $temporaryFingerprint = $null
    }

    [void][IO.Directory]::CreateDirectory($outputDirectory)
    Assert-NoReparsePoint $outputDirectory
    Assert-Hash $unsignedApk $manifest.unsignedApkSha256
    $temporaryApk = Join-Path $outputDirectory ('.signed-' + [Guid]::NewGuid().ToString('N') + '.apk')
    Write-Host 'Подписывается APK и проверяется сертификат…'
    $null = Invoke-ApkSigner @('sign', '--ks', $keystore, '--ks-key-alias', 'private-diary-release',
        '--ks-pass', ('env:' + $script:PasswordEnvironmentName), '--key-pass', ('env:' + $script:PasswordEnvironmentName),
        '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
        '--v4-signing-enabled', 'false', '--out', $temporaryApk, $unsignedApk) 'Подпись APK'
    $verification = Invoke-ApkSigner @('verify', '--verbose', '--print-certs', '--min-sdk-version', '26', $temporaryApk) 'Проверка подписи APK'
    if ($verification -match '(?i)android[\s_-]*debug') { throw 'В APK обнаружен сертификат Android Debug.' }
    $signerHashes = @([regex]::Matches($verification, '(?im)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$'))
    if ($signerHashes.Count -ne 1 -or $signerHashes[0].Groups[1].Value.ToUpperInvariant() -cne $fingerprint) {
        throw 'Сертификат подписанного APK не совпал с вашим ключом.'
    }
    if ($verification -notmatch '(?im)^Verified using v2 scheme .*:\s*true\s*$' -or
        $verification -notmatch '(?im)^Verified using v3 scheme .*:\s*true\s*$') {
        throw 'Не подтверждены обе схемы подписи APK: v2 и v3.'
    }
    $apkHash = (Get-FileHash -LiteralPath $temporaryApk -Algorithm SHA256).Hash
    $temporaryChecksum = Join-Path $outputDirectory ('.sha256-' + [Guid]::NewGuid().ToString('N') + '.txt')
    [IO.File]::WriteAllText($temporaryChecksum, "$apkHash  Private-Diary-1.0.0-RuStore.apk`n", [Text.Encoding]::ASCII)
    Assert-NoReparsePoint $signedApk
    Assert-NoReparsePoint $checksumFile
    [IO.File]::Move($temporaryApk, $signedApk)
    $temporaryApk = $null
    [IO.File]::Move($temporaryChecksum, $checksumFile)
    $temporaryChecksum = $null
    $completed = $true
    Write-Host ''
    Write-Host 'ГОТОВО. В RuStore загружайте только этот подписанный APK:' -ForegroundColor Green
    Write-Host $signedApk
    Write-Host "SHA-256 APK: $apkHash"
    Write-Host "SHA-256 сертификата: $fingerprint"
    Write-Host ''
    Write-Host "Сохраните две офлайн-копии папки ключа: $keyDirectory"
    Write-Host 'Нужны private-diary-release.p12 и publickeyfingerprint.txt. Пароль храните отдельно.'
    Write-Host 'Этот же ключ нужен для всех обновлений. Не отправляйте ключ или пароль в чат и RuStore.'
    Write-Host 'До удаления прежнего GitHub/debug-приложения экспортируйте зашифрованную копию дневника.'
} catch {
    Write-Host ''
    Write-Host ('ОШИБКА: ' + $_.Exception.Message) -ForegroundColor Red
} finally {
    [Environment]::SetEnvironmentVariable($script:PasswordEnvironmentName, $null, 'Process')
    if ($passwordPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer) }
    if ($confirmationPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($confirmationPointer) }
    if ($password) { $password.Dispose() }
    if ($confirmation) { $confirmation.Dispose() }
    foreach ($temporaryPath in @($temporaryKey, $temporaryCertificate, $temporaryApk, $temporaryFingerprint, $temporaryChecksum)) {
        if ($temporaryPath -and (Test-Path -LiteralPath $temporaryPath)) {
            Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
        }
    }
}
if ($completed) { exit 0 }
exit 1
