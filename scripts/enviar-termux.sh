#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
# Executado pelo usuário: prepara uma cópia separada e faz push normal, sem force.
payload="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
repo='https://github.com/giovannitutos-commits/Misticklauncher.git'
project="$HOME/launcher-tvos"
command -v git >/dev/null || { echo 'Instale git: pkg install git'; exit 1; }
command -v gh >/dev/null || { echo 'Instale gh: pkg install gh'; exit 1; }
if ! gh auth status >/dev/null 2>&1; then
  gh auth login --hostname github.com --git-protocol https --web
fi
gh auth setup-git
if [[ ! -e "$project" ]]; then
  git clone --branch main "$repo" "$project"
fi
[[ -d "$project/.git" ]] || { echo "$project já existe e não é um clone git. Renomeie a pasta e tente novamente."; exit 1; }
cd "$project"
origin="$(git remote get-url origin)"
case "$origin" in
  https://github.com/giovannitutos-commits/Misticklauncher|https://github.com/giovannitutos-commits/Misticklauncher.git|git@github.com:giovannitutos-commits/Misticklauncher.git) ;;
  *) echo 'A pasta pertence a outro repositório; nenhum arquivo foi alterado.'; exit 1 ;;
esac
[[ "$(git branch --show-current)" == main ]] || { echo 'Troque para a branch main antes de continuar.'; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Há mudanças locais em $project. Faça commit ou guarde-as antes de executar novamente."; exit 1; }
git pull --ff-only origin main
# A versão anterior fica no histórico Git e ~/launcher não é alterado.
# Substituímos as fontes do módulo app para evitar classes Kotlin duplicadas.
git rm -r --ignore-unmatch -- app/src/main/java app/src/main/kotlin
# Gradle escolhe o Groovy primeiro se ambos os arquivos existirem.
git rm --ignore-unmatch -- build.gradle settings.gradle app/build.gradle
cp "$payload/settings.gradle.kts" "$payload/build.gradle.kts" "$payload/gradle.properties" .
cp "$payload/.gitignore" .
mkdir -p app/src/main .github/workflows scripts
cp "$payload/app/build.gradle.kts" app/
cp -R "$payload/app/src/main/." app/src/main/
cp "$payload/.github/workflows/build.yml" .github/workflows/build.yml
cp "$payload/scripts/instalar-tv.sh" "$payload/scripts/assinatura-termux.sh" scripts/
cp "$payload/README.md" README.md
if ! git config user.name >/dev/null; then
  git config user.name "$(gh api user --jq .login)"
fi
if ! git config user.email >/dev/null; then
  github_id="$(gh api user --jq .id)"
  github_login="$(gh api user --jq .login)"
  git config user.email "${github_id}+${github_login}@users.noreply.github.com"
fi
git add -- settings.gradle.kts build.gradle.kts gradle.properties .gitignore app .github/workflows/build.yml scripts README.md
if ! git diff --cached --quiet; then
  git commit -m 'Launcher tvOS: clima, dock e Watch Next Nuvio/YouTube'
fi
git push -u origin main
echo "Projeto enviado. Pasta para próximas alterações: $project"
echo 'Actions: https://github.com/giovannitutos-commits/Misticklauncher/actions'
echo 'Baixe launcher-apk.zip quando o build ficar verde.'
