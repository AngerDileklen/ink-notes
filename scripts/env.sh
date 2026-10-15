# Source this before building: `source scripts/env.sh`.
# If JAVA_HOME and ANDROID_HOME are already set (Android Studio, CI, a normal dev machine) nothing changes.
# Otherwise set INK_TOOLCHAIN to a folder holding jdk/, sdk/ and gradle/ (an external drive works well).
if [ -z "$JAVA_HOME" ] || [ -z "$ANDROID_HOME" ]; then
  R="${INK_TOOLCHAIN:-/Volumes/ORICO SSD/android-dev}"
  if [ ! -d "$R" ]; then
    echo "Set JAVA_HOME (JDK 17+) and ANDROID_HOME (Android SDK), or INK_TOOLCHAIN to a folder with jdk/ and sdk/." >&2
    return 1
  fi
  export JAVA_HOME="$(ls -d "$R"/jdk/jdk-21*/Contents/Home 2>/dev/null | head -1)"
  export ANDROID_HOME="$R/sdk"
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$R/gradle-home}"
  export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
