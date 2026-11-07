#!/usr/bin/env bash
# Выкладывает код на GitHub (репозиторий Z0rD4n0v1ch/phone-to-tv).
# Запуск: открой Git Bash в этой папке и выполни  bash sync.sh  ["сообщение"]
set -e
cd "$(dirname "$0")"
REPO_URL="https://github.com/Z0rD4n0v1ch/phone-to-tv.git"
MSG="${1:-Обновление $(date '+%Y-%m-%d %H:%M')}"

if [ ! -d .git ]; then
  echo "== Первый запуск: создаю git-репозиторий"
  git init -b main
  git remote add origin "$REPO_URL"
  if git ls-remote --exit-code origin main >/dev/null 2>&1; then
    echo "== На GitHub уже есть файлы — забираю их"
    git fetch origin main
    git reset --soft origin/main 2>/dev/null || true
  fi
fi

git config core.autocrlf false
git update-index --add --chmod=+x gradlew 2>/dev/null || true
git add -A
git update-index --chmod=+x gradlew 2>/dev/null || true
if git diff --cached --quiet; then
  echo "== Изменений нет, выкладывать нечего"
else
  git commit -m "$MSG"
fi

echo "== Отправляю на GitHub"
if ! git push -u origin main; then
  echo
  echo "!! Не получилось отправить. Частые причины:"
  echo "   1) Репозиторий phone-to-tv ещё не создан на github.com (создай пустой, Public, без README)."
  echo "   2) На GitHub есть изменения, которых нет здесь: выполни  git pull --rebase origin main  и запусти скрипт снова."
  echo "   3) Нет входа в GitHub: Git попросит логин; вместо пароля нужен токен (Settings → Developer settings → Tokens)."
  exit 1
fi
echo "== Готово: https://github.com/Z0rD4n0v1ch/phone-to-tv"
