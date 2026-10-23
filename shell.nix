{ pkgs ? import <nixpkgs> { } }:

pkgs.mkShell {
  packages = with pkgs; [
    android-tools
    python3
    jdk17
  ];
}
