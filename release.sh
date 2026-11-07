#!/usr/bin/env bash
# Выпуск новой версии «Экрана на ТВ».
#   bash release.sh 1.1 "Что нового"
# Скрипт выкладывает код на GitHub и ставит тег v1.1. GitHub Actions сам соберёт APK
# и создаст релиз — Tools Box подхватит новую версию автоматически.
set -e
cd "$(dirname "$0")"
VER="${1:-}"; NOTES="${2:-}"
if [ -z "$VER" ]; then
  LAST=$(git tag -l 'v*' --sort=-v:refname | head -1)
  echo "Последняя версия: ${LAST:-нет}"
  read -r -p "Номер новой версии (например, 1.1): " VER
fi
VER="${VER#v}"
if ! [[ "$VER" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]]; then echo "!! Версия должна быть вида 1.1 или 1.1.2"; exit 1; fi
if git rev-parse "v$VER" >/dev/null 2>&1; then echo "!! Версия v$VER уже есть"; exit 1; fi
if [ -z "$NOTES" ]; then read -r -p "Что нового в этой версии? " NOTES; fi
[ -n "$NOTES" ] || NOTES="Версия $VER"

bash sync.sh "Версия $VER: $NOTES"
git tag -a "v$VER" -m "$NOTES"
git push origin "v$VER"

echo
echo "== Тег v$VER отправлен. Сборка идёт на GitHub (3–6 минут):"
echo "   https://github.com/Z0rD4n0v1ch/phone-to-tv/actions"
echo "   Готовый релиз: https://github.com/Z0rD4n0v1ch/phone-to-tv/releases/latest"
