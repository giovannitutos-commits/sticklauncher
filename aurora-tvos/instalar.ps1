param([string]$Apk = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk", [string]$Destino = '192.168.100.8:5555')
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $Apk)) { throw "APK não encontrado: $Apk. Compile ou baixe o artefato AuroraTV-APK do GitHub Actions." }
if (!(Get-Command adb -ErrorAction SilentlyContinue)) { throw 'Instale o Android SDK Platform Tools e adicione adb ao PATH.' }
adb connect $Destino
adb -s $Destino get-state
if ($LASTEXITCODE -ne 0) { throw 'Ative a depuração na TV e autorize este computador na tela da TV.' }
adb -s $Destino install -r $Apk
if ($LASTEXITCODE -ne 0) { throw 'A instalação falhou. O launcher original foi mantido.' }
adb -s $Destino shell am start -n br.com.stocco.auroratv/.MainActivity
