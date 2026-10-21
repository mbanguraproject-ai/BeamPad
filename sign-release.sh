#!/usr/bin/env bash
# Signs the Play bundle GitHub built from the latest main with the upload
# key in keystore.properties: no local Gradle build, which a small
# Codespace cannot hold in memory. Refuses a build from anything but the
# latest main, so an older bundle is never signed by mistake.
#
#   bash sign-release.sh   ->   BeamPad-<version>.aab, ready for Play

cd "$(dirname "$0")" || exit 1
fail() { echo "FAILED: $1"; exit 1; }
prop() { sed -n "s/^$1[[:space:]]*[=:][[:space:]]*//p" keystore.properties | tr -d '\r' | head -1; }
[ -f keystore.properties ] || fail "no keystore.properties in $(pwd)"

repo=mbanguraproject-ai/BeamPad
git fetch -q origin main || fail "git fetch"
sha=$(git rev-parse origin/main | cut -c1-7)
v=$(git show origin/main:app/build.gradle.kts | sed -n 's/.*versionName = "\(.*\)".*/\1/p' | head -1)
vc=$(git show origin/main:app/build.gradle.kts | sed -n 's/.*versionCode = \([0-9_]*\).*/\1/p' | head -1)

# The release notes name the commit the bundle was built from. With the
# Codespace's token: GitHub limits anonymous API calls from shared addresses.
auth=()
[ -n "$GITHUB_TOKEN" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
built=$(curl -fsS "${auth[@]}" "https://api.github.com/repos/$repo/releases/tags/v$v" | grep -o 'Built from [0-9a-f]*' | cut -d' ' -f3)
[ "$built" = "$sha" ] || fail "GitHub's $v bundle is from ${built:-unknown}, main is $sha. Still building: try again in 10 minutes."

rm -f BeamPad-*.aab
curl -fsSL -o "BeamPad-$v-unsigned.aab" "https://github.com/$repo/releases/download/v$v/BeamPad-$v-unsigned.aab" || fail "download"

export BP_STORE_PASS="$(prop storePassword)" BP_KEY_PASS="$(prop keyPassword)"
[ -n "$BP_KEY_PASS" ] || BP_KEY_PASS="$BP_STORE_PASS"
out=$(jarsigner -keystore "$(prop storeFile)" -storepass:env BP_STORE_PASS -keypass:env BP_KEY_PASS \
    -signedjar "BeamPad-$v.aab" "BeamPad-$v-unsigned.aab" "$(prop keyAlias)" 2>&1) || { echo "$out"; fail "signing"; }
jarsigner -verify "BeamPad-$v.aab" 2>&1 | grep -q "jar verified" || fail "the signed bundle did not verify"
rm -f "BeamPad-$v-unsigned.aab"

echo "READY: BeamPad-$v.aab  (version $v, code $vc, built from $sha)"
