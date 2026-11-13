# Aurora TV — Mi TV Stick

Launcher Android TV inspirado no tvOS: fundo aurora, painéis translúcidos, foco animado pelo controle, dock de favoritos e clima por cidade. Interface nativa, sem WebView e sem bibliotecas externas de interface.

## Uso

- Setas: navegar. OK: abrir.
- Segurar OK em um aplicativo: adicionar/remover do dock ou mover para a esquerda/direita.
- Clima e cidade: buscar a cidade, escolher entre os resultados e exibir temperatura, condição, máxima e mínima.
- Continuar assistindo: lê Watch Next do Android TV, com imagem, progresso e link de retorno ao aplicativo. Use “Permitir acesso aos conteúdos” se solicitado.
- Os conteúdos dependem de publicação pelos apps e das permissões do firmware. Não há garantia de que YouTube ou Nuvio publiquem títulos. Sem dados, aparecem atalhos identificados para abrir os aplicativos; eles não simulam histórico.
- Para voltar ao início com o botão HOME: selecione Aurora TV como launcher padrão se a TV oferecer essa escolha. O instalador não desativa o launcher de fábrica.

## Compilar

Requer JDK 17, Android SDK (plataforma 35), Gradle 8.9 e internet para baixar as ferramentas.

Na raiz deste projeto:
```
gradle assembleDebug lintDebug
```

APK: app/build/outputs/apk/debug/app-debug.apk

Alternativa: envie o conteúdo desta pasta para a raiz de um repositório GitHub. O workflow “Compilar APK”, em .github/workflows/apk.yml, roda em push ou manualmente pela aba Actions. Baixe o artefato AuroraTV-APK e extraia o APK.

O APK de desenvolvimento usa a chave debug do ambiente de compilação. Atualizações exigem a mesma chave; guarde-a para manter os dados entre versões ou configure assinatura permanente antes da distribuição.

## Instalar na Mi TV Stick

Destino configurado: 192.168.100.8:5555.

1. Na TV, abra Configurações > Preferências do dispositivo > Sobre. Pressione repetidamente “Build” para ativar as opções do desenvolvedor.
2. Ative depuração USB e, se houver, depuração pela rede. O nome varia conforme o firmware.
3. Instale Android SDK Platform Tools no computador e adicione adb ao PATH.
4. Execute no PowerShell:
```
.\instalar.ps1 -Apk "C:\caminho\app-debug.apk"
```
5. Aceite a autorização de depuração na tela da TV.

Se a TV oferecer depuração sem fio por código, use “adb pair IP:PORTA” e depois o endereço de conexão mostrado pela própria TV; passe esse endereço em -Destino. A porta 5555 só funciona quando o firmware a disponibiliza.

## Estado da entrega

Código criado e arquivos XML conferidos. APK ainda não compilado nem testado em TV: o ambiente desta entrega não tem JDK, Gradle, SDK Android ou adb, e o download dessas ferramentas foi bloqueado por conectividade. A TV respondeu ao ping, mas a conexão TCP na porta 5555 falhou. A integração Watch Next e o layout precisam de validação no aparelho após compilar.

## Clima e atribuição

Clima fornecido por Open-Meteo, com atribuição CC BY 4.0: https://open-meteo.com/
Geocodificação: Open-Meteo / GeoNames: https://www.geonames.org/

A consulta envia a cidade escolhida ao serviço de geocodificação e coordenadas ao serviço meteorológico. Não solicita GPS ou conta. A API pública gratuita é destinada ao uso não comercial; para distribuição comercial, verifique os termos e o plano apropriado.

Referências:
- https://developer.android.com/training/tv/discovery/watch-next-add-programs
- https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/main/AndroidManifest.xml

