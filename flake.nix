{
  description = "Kodakiko development shell: Android SDK and JDK for the Kotlin Android app (apps/android)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/d407951447dcd00442e97087bf374aad70c04cea";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };
        ndkVersion = "27.0.12077973";
        buildToolsVersion = "37.0.0";
        cmakeVersion = "3.22.1";
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "37" ];
          buildToolsVersions = [ buildToolsVersion "36.0.0" ];
          includeNDK = true;
          ndkVersions = [ ndkVersion ];
          cmakeVersions = [ cmakeVersion ];
          includeEmulator = false;
          includeSystemImages = false;
        };
        androidSdk = androidComposition.androidsdk;
        androidSdkRoot = "${androidSdk}/libexec/android-sdk";
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [ androidSdk pkgs.jdk17 pkgs.scrcpy pkgs.resvg ];
          shellHook = ''
            export ANDROID_HOME="${androidSdkRoot}"
            export ANDROID_SDK_ROOT="${androidSdkRoot}"
            export JAVA_HOME="${pkgs.jdk17.home}"
            export KODAKIKO_NDK_VERSION="${ndkVersion}"
            export KODAKIKO_CMAKE_VERSION="${cmakeVersion}"
            export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdkRoot}/build-tools/${buildToolsVersion}/aapt2"
            export PATH="${androidSdkRoot}/platform-tools:${androidSdkRoot}/cmdline-tools/latest/bin:$PATH"
          '';
        };
      });
}
