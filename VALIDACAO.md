# Validação da entrega

- `gradle :app:assembleRelease :app:lintRelease`: **BUILD SUCCESSFUL**.
- Assinatura do APK local verificada com Android apksigner.
- Application ID `br.com.mi.atv.launcher`, Activity `.MainActivity`, API mínima 26.
- Android Lint: zero erros. Avisos restantes sobre target SDK, orientação fixa de TV, vetores, configuração de backup, fluxo de fechamento de InputStream (fechado por try-with-resources) e textos em português montados no código.
- Scripts shell: sintaxe verificada.
- Envio Termux testado com repositório Git local de teste e autenticação simulada: migração das fontes, cópia do workflow, commit/push, reexecução sem novo commit e parada ao detectar mudanças locais.
- Instalação ADB testada com implementação simulada: fluxo normal, ZIP com espaços no nome, endereço inválido e erro de assinatura sem desinstalação automática.
- Nenhum comando foi enviado à sua TV e nenhum commit foi publicado no seu GitHub por esta sessão.
- Sem teste em aparelho ou emulador: foco real do controle, desempenho no Mi TV Stick, permissões do firmware, disponibilidade de conteúdos Nuvio/YouTube e resposta do serviço de clima no aparelho devem ser verificados após instalar.
- O APK local usa uma chave debug local de teste. O GitHub gera o seu próprio APK com a chave configurada pelo workflow.
