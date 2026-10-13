# Source this: toolchain lives on the external SSD so the Mac's internal disk stays free.
# usage: source scripts/env.sh
R="/Volumes/ORICO SSD/android-dev"
if [ ! -d "$R" ]; then echo "ORICO SSD is not mounted; plug it in first." >&2; return 1; fi
export JAVA_HOME="$(ls -d "$R"/jdk/jdk-21*/Contents/Home)"
export ANDROID_HOME="$R/sdk"
export ANDROID_SDK_ROOT="$R/sdk"
export GRADLE_USER_HOME="$R/gradle-home"
export PATH="$JAVA_HOME/bin:$R/gradle/gradle-8.9/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
