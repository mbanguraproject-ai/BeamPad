#!/usr/bin/env bash
# Signs the Play bundle and the APK GitHub built from the latest main with
# the upload key in keystore.properties: no local Gradle build, which a
# small Codespace cannot hold in memory. Refuses a build from anything but
# the latest main, so an older one is never signed by mistake.
#
#   bash sign-release.sh   ->   BeamPad-<version>.aab, for Play
#                               BeamPad-<version>.apk, to install directly

cd "$(dirname "$0")" || exit 1
fail() { echo "FAILED: $1"; exit 1; }
prop() { sed -n "s/^$1[[:space:]]*[=:][[:space:]]*//p" keystore.properties | tr -d '\r' | head -1; }
[ -f keystore.properties ] || fail "no keystore.properties in $(pwd)"

repo=mbanguraproject-ai/BeamPad
git fetch -q origin main || fail "git fetch"
v=$(git show origin/main:app/build.gradle.kts | sed -n 's/.*versionName = "\(.*\)".*/\1/p' | head -1)
vc=$(git show origin/main:app/build.gradle.kts | sed -n 's/.*versionCode = \([0-9_]*\).*/\1/p' | head -1)

# The release notes name the commit the bundle was built from; it must
# include the latest change to the app (a change to this script or the docs
# alone needs no new build). With the Codespace's token: GitHub limits
# anonymous API calls from shared addresses.
auth=()
[ -n "$GITHUB_TOKEN" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
built=$(curl -fsS "${auth[@]}" "https://api.github.com/repos/$repo/releases/tags/v$v" | grep -o 'Built from [0-9a-f]*' | cut -d' ' -f3)
app=$(git log -1 --format=%h origin/main -- app wear gradle gradle.properties build.gradle.kts settings.gradle.kts)
[ -n "$built" ] && git merge-base --is-ancestor "$app" "$built" 2>/dev/null ||
    fail "GitHub's $v build is from ${built:-unknown}, before the latest app change ($app). Still building: try again in 10 minutes."
sha=$built

# The APK needs apksigner (Android checks its v2/v3 signature), from the
# SDK's newest build-tools, or from the PATH.
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}}
bt=$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
tool() { if [ -n "$bt" ] && [ -x "$bt$1" ]; then echo "$bt$1"; else command -v "$1"; fi; }
apksigner=$(tool apksigner)
zipalign=$(tool zipalign)
[ -n "$apksigner" ] && [ -n "$zipalign" ] || fail "apksigner and zipalign not found (Android SDK build-tools)"

rm -f BeamPad-*.aab BeamPad-*.apk BeamPad-*.idsig
for f in aab apk; do
    curl -fsSL -o "BeamPad-$v-unsigned.$f" "https://github.com/$repo/releases/download/v$v/BeamPad-$v-unsigned.$f" || fail "download ($f)"
done

export BP_STORE_PASS="$(prop storePassword)" BP_KEY_PASS="$(prop keyPassword)"
[ -n "$BP_KEY_PASS" ] || BP_KEY_PASS="$BP_STORE_PASS"
out=$(jarsigner -keystore "$(prop storeFile)" -storepass:env BP_STORE_PASS -keypass:env BP_KEY_PASS \
    -signedjar "BeamPad-$v.aab" "BeamPad-$v-unsigned.aab" "$(prop keyAlias)" 2>&1) || { echo "$out"; fail "signing"; }
jarsigner -verify "BeamPad-$v.aab" 2>&1 | grep -q "jar verified" || fail "the signed bundle did not verify"

"$zipalign" -p -f 4 "BeamPad-$v-unsigned.apk" "BeamPad-$v-aligned.apk" || fail "zipalign"
out=$("$apksigner" sign --ks "$(prop storeFile)" --ks-key-alias "$(prop keyAlias)" \
    --ks-pass env:BP_STORE_PASS --key-pass env:BP_KEY_PASS \
    --out "BeamPad-$v.apk" "BeamPad-$v-aligned.apk" 2>&1) || { echo "$out"; fail "signing the APK"; }
"$apksigner" verify "BeamPad-$v.apk" >/dev/null 2>&1 || fail "the signed APK did not verify"

rm -f BeamPad-*-unsigned.* "BeamPad-$v-aligned.apk" BeamPad-*.idsig
echo "READY: BeamPad-$v.aab  (for Play)"
echo "READY: BeamPad-$v.apk  (to install)"
echo "       version $v, code $vc, built from $sha"
