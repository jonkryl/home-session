#!/usr/bin/env bash
set -euo pipefail
mkdir -p release-output
apk="release-output/home-session-${HOME_SESSION_VERSION_NAME}-${HOME_SESSION_VERSION_CODE}.apk"
aab="release-output/home-session-${HOME_SESSION_VERSION_NAME}-${HOME_SESSION_VERSION_CODE}.aab"
cp app/build/outputs/apk/release/app-release.apk "$apk"
cp app/build/outputs/bundle/release/app-release.aab "$aab"
build_tools="${ANDROID_HOME}/build-tools/36.0.0"
test -x "$build_tools/apksigner"
"$build_tools/apksigner" verify --verbose --print-certs "$apk" > release-output/apk-signature.txt
"$build_tools/zipalign" -c -P 16 -v 4 "$apk" > release-output/apk-alignment.txt
jarsigner -verify -verbose -certs "$aab" > release-output/aab-signature.txt
grep -q 'jar verified' release-output/aab-signature.txt
keytool -printcert -jarfile "$aab" > release-output/aab-certificate.txt
curl --fail --silent --show-error --location https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar -o "$RUNNER_TEMP/home-session-bundletool.jar"
printf '%s  %s\n' 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29' "$RUNNER_TEMP/home-session-bundletool.jar" | sha256sum --check
java -jar "$RUNNER_TEMP/home-session-bundletool.jar" validate --bundle="$aab" > release-output/aab-validation.txt
java -jar "$RUNNER_TEMP/home-session-bundletool.jar" dump manifest --bundle="$aab" --module=base > release-output/aab-manifest.xml
"$build_tools/aapt" dump badging "$apk" > release-output/package.txt
"$build_tools/aapt" dump xmltree "$apk" AndroidManifest.xml > release-output/merged-manifest.txt
if grep -q 'com.google.android.gms.permission.AD_ID' release-output/merged-manifest.txt; then
    echo 'The release unexpectedly requests advertising ID access' >&2
    exit 1
fi
keytool -list -v -keystore "$HOME_SESSION_KEYSTORE_FILE" -alias "$HOME_SESSION_KEY_ALIAS" -storepass:env HOME_SESSION_KEYSTORE_PASSWORD > "$RUNNER_TEMP/home-session-expected-certificate.txt"
python3 scripts/verify-certificate.py release-output/apk-signature.txt "$RUNNER_TEMP/home-session-expected-certificate.txt"
python3 scripts/verify-certificate.py release-output/aab-certificate.txt "$RUNNER_TEMP/home-session-expected-certificate.txt"
keytool -exportcert -rfc -keystore "$HOME_SESSION_KEYSTORE_FILE" -alias "$HOME_SESSION_KEY_ALIAS" -storepass:env HOME_SESSION_KEYSTORE_PASSWORD > release-output/upload-certificate.pem
python3 scripts/verify-elf-alignment.py "$apk" > release-output/native-page-alignment.txt
sha256sum "$apk" "$aab" > release-output/SHA256SUMS
python3 scripts/record-release.py "$apk" "$aab"
