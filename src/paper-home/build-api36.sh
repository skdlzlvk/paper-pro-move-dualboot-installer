#!/bin/sh
set -eu

project="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
output="${1:?usage: build-api36.sh OUTPUT_DIRECTORY}"
aosp="${PAPER_HOME_AOSP:?set PAPER_HOME_AOSP to an Android 16 source tree}"
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-315532800}"
export TZ=UTC
jdk="$aosp/prebuilts/jdk/jdk21/linux-x86/bin"
aapt2="$aosp/prebuilts/sdk/tools/linux/bin/aapt2"
zipalign="$aosp/prebuilts/build-tools/linux-x86/bin/zipalign"

for required in \
    "$aosp/prebuilts/sdk/36/public/android.jar" \
    "$aosp/prebuilts/r8/r8.jar" \
    "$aosp/prebuilts/sdk/tools/linux/lib/apksigner.jar" \
    "$aosp/build/make/target/product/security/testkey.pk8" \
    "$aosp/build/make/target/product/security/testkey.x509.pem" \
    "$jdk/java" "$jdk/javac" "$jdk/jar" "$aapt2" "$zipalign"; do
    test -f "$required" || {
        printf 'missing Android 16 build input: %s\n' "$required" >&2
        exit 1
    }
done

test ! -e "$output" || {
    printf 'refusing to overwrite build directory: %s\n' "$output" >&2
    exit 1
}
mkdir -p "$output/classes" "$output/dex" "$output/generated"
cp "$aosp/prebuilts/sdk/36/public/android.jar" "$output/android.jar"
cp "$aosp/prebuilts/r8/r8.jar" "$output/r8.jar"
cp "$aosp/prebuilts/sdk/tools/linux/lib/apksigner.jar" "$output/apksigner.jar"
cp "$aosp/build/make/target/product/security/testkey.pk8" "$output/testkey.pk8"
cp "$aosp/build/make/target/product/security/testkey.x509.pem" "$output/testkey.x509.pem"

"$aapt2" compile --dir "$project/res" -o "$output/compiled-res.zip"
"$aapt2" link \
    -o "$output/PaperHome-unsigned.apk" \
    -I "$output/android.jar" \
    --java "$output/generated" \
    --manifest "$project/AndroidManifest.xml" \
    --min-sdk-version 28 --target-sdk-version 36 \
    "$output/compiled-res.zip"
find "$project/src" "$output/generated" \
    -type f -name '*.java' -print | sort >"$output/java-sources.list"
"$jdk/javac" --release 8 -Xlint:-options \
    -classpath "$output/android.jar" -d "$output/classes" \
    @"$output/java-sources.list"
(
    cd "$output/classes"
    "$jdk/jar" cf "$output/classes.jar" .
)
"$jdk/java" -Xmx2G -cp "$output/r8.jar" com.android.tools.r8.D8 \
    --lib "$output/android.jar" --min-api 28 \
    --output "$output/dex" "$output/classes.jar"
(
    cd "$output"
    touch -d "@$SOURCE_DATE_EPOCH" dex/classes.dex
    zip -X -q -j PaperHome-unsigned.apk dex/classes.dex
)
"$zipalign" -f 4 "$output/PaperHome-unsigned.apk" "$output/PaperHome-aligned.apk"
"$jdk/java" -jar "$output/apksigner.jar" sign \
    --key "$output/testkey.pk8" --cert "$output/testkey.x509.pem" \
    --out "$output/PaperHome.apk" "$output/PaperHome-aligned.apk"
"$jdk/java" -jar "$output/apksigner.jar" verify \
    --verbose --print-certs "$output/PaperHome.apk"
sha256sum "$output/PaperHome.apk"
