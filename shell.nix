{ pkgs ? import <nixpkgs> {} }:

let
  glibcLib = "${pkgs.glibc.out}/lib";
  dynamicLinker = "${pkgs.glibc.out}/lib/ld-linux-x86-64.so.2";
in
pkgs.mkShell {
  packages = with pkgs; [
    android-tools
    findutils
    gh
    git
    gnused
    jdk17
    patchelf
    ripgrep
  ];

  shellHook = ''
    export JAVA_HOME=${pkgs.jdk17}
    export GRADLE_USER_HOME="''${GRADLE_USER_HOME:-/tmp/omsyncer-gradle}"

    if [ -z "''${ANDROID_SDK_ROOT:-}" ] && [ -f local.properties ]; then
      export ANDROID_SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' local.properties | head -n1)"
    fi

    if [ -n "''${ANDROID_SDK_ROOT:-}" ] && [ -z "''${ANDROID_HOME:-}" ]; then
      export ANDROID_HOME="$ANDROID_SDK_ROOT"
    fi

    prepare_omsyncer_android_tools() {
      local sdk_root="''${ANDROID_SDK_ROOT:-''${ANDROID_HOME:-}}"
      local aapt2_src
      local adb_src

      if [ -z "$sdk_root" ]; then
        echo "ANDROID_SDK_ROOT is not set. Keep local.properties with sdk.dir or export ANDROID_SDK_ROOT."
        return 1
      fi

      aapt2_src="$(find "$sdk_root/build-tools" -maxdepth 2 -type f -name aapt2 | sort -V | tail -n1)"
      if [ -z "$aapt2_src" ]; then
        echo "Could not find aapt2 under $sdk_root/build-tools."
        return 1
      fi

      cp "$aapt2_src" /tmp/omsyncer-aapt2
      patchelf \
        --set-interpreter ${dynamicLinker} \
        --set-rpath ${glibcLib} \
        /tmp/omsyncer-aapt2
      cp /tmp/omsyncer-aapt2 /tmp/aapt2
      export OMSYNCER_AAPT2=/tmp/aapt2

      adb_src="$sdk_root/platform-tools/adb"
      if [ ! -f "$adb_src" ]; then
        echo "Could not find adb under $sdk_root/platform-tools."
        return 1
      fi

      cp "$adb_src" /tmp/omsyncer-adb
      patchelf \
        --set-interpreter ${dynamicLinker} \
        --set-rpath ${glibcLib} \
        /tmp/omsyncer-adb
      cp /tmp/omsyncer-adb /tmp/adb
      export OMSYNCER_ADB=/tmp/adb
    }

    gradlew-nix() {
      prepare_omsyncer_android_tools || return 1
      ./gradlew -Pandroid.aapt2FromMavenOverride="$OMSYNCER_AAPT2" "$@"
    }

    adb-nix() {
      prepare_omsyncer_android_tools || return 1
      "$OMSYNCER_ADB" "$@"
    }

    echo "OmSyncer nix-shell ready. Use gradlew-nix <tasks> and adb-nix <args> on NixOS."
  '';
}
