# MiStick Launcher 5 — visual inspirado no tvOS

Projeto para Mi TV Stick Full HD. Pacote preservado: `br.com.mi.atv.launcher`. Activity preservada: `.MainActivity`.

## Como enviar pelo Termux

1. Baixe `mistick-tvos.zip` para Downloads.
2. No Termux, rode:

```bash
pkg update -y
pkg install -y git gh unzip python android-tools coreutils
termux-setup-storage
```

Autorize o acesso aos arquivos do celular. Extraia o ZIP em uma pasta dentro do HOME do Termux (não compile em armazenamento compartilhado):

```bash
mkdir -p "$HOME/mistick-pacote"
python - <<'PY'
from pathlib import Path
import zipfile
p=Path.home()/'storage/downloads'
files=sorted(p.glob('mistick-tvos*.zip'),key=lambda f:f.stat().st_mtime,reverse=True)
if not files:raise SystemExit('Baixe mistick-tvos.zip para Downloads primeiro.')
with zipfile.ZipFile(files[0]) as z:
 dest=Path.home()/'mistick-pacote'
 for n in z.namelist():
  if not (dest/n).resolve().is_relative_to(dest.resolve()):raise SystemExit('Caminho de ZIP inválido.')
 z.extractall(dest)
print('Extraído em',dest)
PY
bash "$HOME/mistick-pacote/mistick-tvos/scripts/enviar-termux.sh"
```

O script pede login no GitHub pelo `gh` se necessário, configura a autenticação Git, clona `giovannitutos-commits/Misticklauncher` em `~/launcher-tvos`, atualiza os arquivos, cria um commit e envia para `main`. **Não usa force push, não altera `~/launcher` e não armazena tokens no código.** Se `~/launcher-tvos` já existir com mudanças locais, o script para antes de copiar arquivos; guarde as mudanças antes de tentar novamente. Se houver commits remotos divergentes, o pull ff-only para em vez de descartar histórico.

A cópia nova remove do módulo as fontes Java/Kotlin anteriores e usa a interface nativa desta versão. As versões antigas continuam no histórico do repositório. O build.yml atual é substituído; se você adicionou outros workflows no futuro, verifique-os para evitar compilações duplicadas.

Depois abra:

https://github.com/giovannitutos-commits/Misticklauncher/actions

Espere **Build APK** ficar verde, abra a execução e baixe o artefato **launcher-apk**. O navegador geralmente o salva como `launcher-apk.zip`. Dentro há `app-release.apk`.

## Instalar na TV pelo Termux

Computador e runner local não são necessários: a conexão ADB sai do celular para a TV na mesma rede.

```bash
bash "$HOME/launcher-tvos/scripts/instalar-tv.sh"
```

O script extrai apenas `app-release.apk` do ZIP `launcher-apk*.zip` mais recente de Downloads, conecta a `192.168.100.4:5555`, aguarda até 45 segundos, instala com `install -r`, tenta conceder `READ_TV_LISTINGS` e abre a Activity.

Se baixar o APK diretamente, informe seu caminho:

```bash
bash "$HOME/launcher-tvos/scripts/instalar-tv.sh" "$HOME/storage/downloads/app-release.apk"
```

Para outro endereço de TV:

```bash
TV_ADB_ADDRESS=192.168.100.4:5555 bash "$HOME/launcher-tvos/scripts/instalar-tv.sh"
```

Ative a depuração de rede compatível com seu firmware e aceite a autorização RSA na TV. Se a conexão falhar, confirme IP, porta e se ambos estão no mesmo Wi-Fi sem isolamento de clientes. Habilitar depuração USB não garante que a porta de rede 5555 esteja aberta. Não exponha ADB na internet.

O script não desinstala automaticamente nem muda o launcher padrão.

## Assinatura estável: configure para preservar futuras atualizações

O workflow gera um APK release assinado com uma chave debug de testes, como o projeto anterior. Um cache guarda a chave entre builds. Caches podem expirar: configure uma chave persistente como secret `MISTICK_KEYSTORE_B64`.

Se você já tem a chave que assinou o launcher instalado, use **essa mesma chave**. O workflow espera o formato debug padrão (alias `androiddebugkey`, senha da chave e do keystore `android`). Não substitua sua chave original por uma nova se deseja atualizar preservando os dados.

Se não possui uma chave antiga e está começando uma nova sequência de versões de teste, este comando gera uma chave no Termux e a envia como secret sem imprimi-la:

```bash
pkg install -y openjdk-17
bash "$HOME/launcher-tvos/scripts/assinatura-termux.sh"
gh workflow run build.yml --repo giovannitutos-commits/Misticklauncher --ref main
```

Preserve `~/.mistick-signing/debug.keystore`. Não envie o arquivo ao Git. **Uma nova chave pode ser incompatível com a versão já instalada.** Nesse caso a instalação falha com `INSTALL_FAILED_UPDATE_INCOMPATIBLE` e seus dados permanecem intactos. Só se você aceitar perder os ajustes do launcher antigo, remova-o manualmente e rode a instalação de novo:

```bash
adb -s 192.168.100.4:5555 uninstall br.com.mi.atv.launcher
```

A primeira execução de `enviar-termux.sh` já dispara o build. O passo da assinatura persistente é recomendado antes de instalar a primeira versão nova; após configurá-la, gere outro APK com `gh workflow run` e instale esse novo APK.

## Interface e controles

- Fundo costeiro estático incluído no APK, sem download e sem vídeo de fundo.
- Previsão no canto superior esquerdo: temperatura atual, condição, máxima/mínima e cidade. Clique no clima para escolher a cidade em uma busca com resultados. Atualização a cada 30 minutos enquanto o launcher está ativo; cache indica desatualização quando necessário.
- Relógio e ajustes no canto superior direito.
- Fileira **Continuar assistindo** para conteúdos publicados no Watch Next por Nuvio e YouTube. Thumbnail, título, origem e progresso real quando publicado.
- Dock translúcido com apps fixados e grade com os demais, sem repetir fixados.
- Direcional navega; OK abre; segure OK em um app para fixar/desafixar.
- Engrenagem → Organizar aplicativos da dock → Reordenar fixados permite mover apps à esquerda/direita.
- Ajustes usam diálogos nativos, que isolam o foco do controle em relação à tela de fundo.
- Voltar retorna ao topo.

A referência visual foi adaptada para Views nativas, usando o banner do próprio app quando existe. Não é uma reprodução pixel a pixel do mockup. O vidro usa transparência e borda; não há blur em tempo real. O launcher não inclui Netflix/Nuvio/YouTube ou qualquer conteúdo de streaming.

## Limite importante: Nuvio e YouTube

O aplicativo não lê diretamente o histórico privado da sua conta. Ele consulta `content://android.media.tv/watch_next_program` com `android.permission.READ_TV_LISTINGS`, filtra Nuvio/YouTube e abre o deep link fornecido pelo app de origem.

Se sua versão de Nuvio/YouTube não publicar Watch Next, não haverá cartões reais, mesmo com a permissão concedida. Nessa situação, aparecem atalhos **Abrir Nuvio / Abrir YouTube**, identificados como sem cartões de continuação. Não são mostradas recomendações como se fossem histórico, e não são usados dados fictícios.

Para verificar: Ajustes → Permitir / atualizar Continuar assistindo; depois Ajustes → Diagnóstico do Nuvio e YouTube. Assista a algo nesses apps, volte ao launcher e confira. Conteúdos e metadados variam conforme app e firmware. Não há garantia de acesso ao histórico do YouTube.

## Privacidade e serviços

O clima consulta Open-Meteo por HTTPS, usando a cidade e coordenadas escolhidas manualmente. Não solicita GPS, login, chave de API pessoal nem coleta telemetria. Fonte: Open-Meteo; localidades: GeoNames via Open-Meteo. Esta implementação usa o endpoint gratuito para uso pessoal não comercial.

Capas dos cartões são carregadas de URIs de conteúdo permitidas ou HTTPS, com tamanho limitado, cache em memória e decodificação reduzida. URLs HTTP sem TLS não são carregadas nesta versão.

## Compilar localmente

Java 17, Gradle 8.9, Android SDK 35 e Build Tools 34.0.0:

```bash
gradle :app:assembleRelease :app:lintRelease
```

O workflow instala o Gradle com `gradle/actions/setup-gradle` e o SDK com `android-actions/setup-android`; o projeto não precisa de wrapper. Nenhuma dependência de runtime de terceiros, Compose ou Kotlin é usada. O código mantém o applicationId do projeto anterior, mas esta é uma nova implementação de tela principal.

## Launcher padrão

Abra o app antes de decidir torná-lo padrão. Se o firmware disponibilizar seleção de app inicial, escolha MiStick Launcher. Não remova nem desative o launcher original. Alguns firmwares interceptam o botão Home; esta entrega não contorna esse comportamento.

## Referências técnicas

- https://developer.android.com/training/tv/get-started/create
- https://developer.android.com/reference/android/media/tv/TvContract.WatchNextPrograms
- https://android.googlesource.com/platform/packages/providers/TvProvider/+/master/src/com/android/providers/tv/TvProvider.java
- https://open-meteo.com/en/docs
- https://open-meteo.com/en/docs/geocoding-api
- https://developer.android.com/tools/adb

## Verificações desta entrega

Compilação release e Android Lint executados com sucesso; veja `VALIDACAO.md`. Ainda não testado em TV ou emulador.
