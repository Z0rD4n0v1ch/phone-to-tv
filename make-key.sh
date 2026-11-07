#!/usr/bin/env bash
# Создаёт постоянный ключ подписи для APK (делается ОДИН раз).
# Без своего ключа каждая сборка на GitHub подписывается новым временным ключом,
# и обновление не встаёт поверх старой версии.
#   bash make-key.sh
set -e
KEY_DIR="$HOME/.config/phone-to-tv"
KEY_FILE="$KEY_DIR/release.jks"
ALIAS="phonetotv"

# keytool есть в составе Android Studio
KT=$(command -v keytool || true)
for p in "/c/Program Files/Android/Android Studio/jbr/bin/keytool.exe" "$LOCALAPPDATA/Programs/Android Studio/jbr/bin/keytool.exe"; do
  [ -z "$KT" ] && [ -f "$p" ] && KT="$p"
done
[ -n "$KT" ] || { echo "!! Не найден keytool. Укажи путь: KT=\"/c/.../jbr/bin/keytool.exe\" bash make-key.sh"; exit 1; }

mkdir -p "$KEY_DIR"
if [ -f "$KEY_FILE" ]; then
  echo "== Ключ уже есть: $KEY_FILE (новый не создаю — иначе обновления перестанут ставиться)"
else
  read -r -s -p "Придумай пароль для ключа (минимум 6 символов): " PASS; echo
  "$KT" -genkeypair -v -keystore "$KEY_FILE" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=PhoneToTv, O=Z0rD4n0v1ch, C=EE"
  echo "== Ключ создан: $KEY_FILE"
  echo "   Сохрани копию этого файла и пароль (например, на флешку). Потеряешь — обновления не будут ставиться поверх."
fi

base64 -w0 "$KEY_FILE" > "$KEY_DIR/keystore.base64.txt"
echo
echo "== Теперь добавь 4 секрета на GitHub:"
echo "   github.com/Z0rD4n0v1ch/phone-to-tv → Settings → Secrets and variables → Actions → New repository secret"
echo "   KEYSTORE_BASE64   — всё содержимое файла $KEY_DIR/keystore.base64.txt"
echo "   KEYSTORE_PASSWORD — пароль ключа"
echo "   KEY_ALIAS         — $ALIAS"
echo "   KEY_PASSWORD      — тот же пароль"
(notepad "$(cygpath -w "$KEY_DIR/keystore.base64.txt" 2>/dev/null || echo "$KEY_DIR/keystore.base64.txt")" 2>/dev/null &) || true
