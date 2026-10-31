#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
address="${TV_ADB_ADDRESS:-192.168.100.4:5555}"
[[ "$address" =~ ^[a-zA-Z0-9.-]+:[0-9]{1,5}$ ]] || { echo 'Use TV_ADB_ADDRESS=IP:porta'; exit 2; }
command -v adb >/dev/null || { echo 'Instale ADB: pkg install android-tools'; exit 1; }
if [[ $# -gt 0 ]]; then
  apk="$1"
else
  command -v python >/dev/null || { echo 'Instale Python: pkg install python'; exit 1; }
  apk="$(python - <<'PY'
from pathlib import Path
import zipfile,tempfile
p=Path.home()/'storage/downloads'
files=sorted(p.glob('launcher-apk*.zip'),key=lambda f:f.stat().st_mtime,reverse=True)
if not files:raise SystemExit('Baixe launcher-apk.zip do GitHub Actions para Downloads primeiro.')
with zipfile.ZipFile(files[0]) as z:
 names=[n for n in z.namelist() if n=='app-release.apk' or n.endswith('/app-release.apk')]
 if len(names)!=1:raise SystemExit('ZIP não contém exatamente um app-release.apk.')
 dest=Path(tempfile.mkdtemp(prefix='mistick-apk-'))/'app-release.apk'
 dest.write_bytes(z.read(names[0]))
 print(dest)
PY
)"
fi
[[ -f "$apk" ]] || { echo 'APK não encontrado'; exit 1; }
adb connect "$address"
echo 'Se aparecer pedido de autorização na TV, aceite-o pelo controle.'
timeout 45 adb -s "$address" wait-for-device || { echo 'TV não respondeu em 45s. Confira IP, rede e depuração.'; exit 1; }
[[ "$(adb -s "$address" get-state)" == device ]] || { echo 'ADB não autorizado ou offline'; exit 1; }
# Falha de assinatura NÃO dispara desinstalação automática: mantém os dados intactos.
adb -s "$address" install -r "$apk"
if ! adb -s "$address" shell pm grant br.com.mi.atv.launcher android.permission.READ_TV_LISTINGS; then
  echo 'Permissão recusada pelo firmware. Tente Ajustes > Permitir Continuar assistindo no launcher.'
fi
adb -s "$address" shell am start -n br.com.mi.atv.launcher/.MainActivity
echo 'Instalado. Clique na previsão para escolher a cidade; segure OK em apps para fixar/remover.'
