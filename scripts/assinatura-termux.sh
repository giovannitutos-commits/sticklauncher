#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
command -v keytool >/dev/null || { echo 'Instale Java no Termux: pkg install openjdk-17'; exit 1; }
command -v gh >/dev/null || { echo 'Instale gh: pkg install gh'; exit 1; }
umask 077
mkdir -p "$HOME/.mistick-signing"
key="$HOME/.mistick-signing/debug.keystore"
if [[ ! -f "$key" ]]; then
  keytool -genkeypair -keystore "$key" -storepass android -alias androiddebugkey -keypass android -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
fi
# Nunca imprime a chave e nunca a adiciona ao repositório.
base64 < "$key" | tr -d '\n' | gh secret set MISTICK_KEYSTORE_B64 --repo giovannitutos-commits/Misticklauncher
echo 'Assinatura configurada. Preserve ~/.mistick-signing/debug.keystore para futuras atualizações.'
echo 'A primeira instalação pode exigir remover a versão antiga se ela foi assinada com outra chave.'
