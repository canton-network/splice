{
  description = "splice nix setup for development";

  inputs = {
    nixpkgs.url = "nixpkgs/nixpkgs-unstable";
    # Pinned to the last nixpkgs-unstable commit before google-cloud-sdk switched its
    # bundled Python from 3.12 to 3.14
    nixpkgs-for-gcloud.url = "github:NixOS/nixpkgs/7e694d87970c8a280ac5420a5af2738a63ed2711";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, nixpkgs-for-gcloud, flake-utils }:
    flake-utils.lib.eachDefaultSystem
      (system:
        let pkgs-for-gcloud = import nixpkgs-for-gcloud { inherit system; };
            enterprise_pkgs = import nixpkgs { inherit system; overlays = import ./overlays.nix { use_enterprise = true; inherit pkgs-for-gcloud; }; };
            oss_pkgs = import nixpkgs { inherit system; overlays = import ./overlays.nix { use_enterprise = false; inherit pkgs-for-gcloud; }; };
            enterprise_x86Pkgs =
              if system == "aarch64-darwin"
              then import nixpkgs { system = "x86_64-darwin"; overlays = import ./overlays.nix { use_enterprise = true; pkgs-for-gcloud = import nixpkgs-for-gcloud { system = "x86_64-darwin"; }; }; }
              else enterprise_pkgs;
            oss_x86Pkgs =
              if system == "aarch64-darwin"
              then import nixpkgs { system = "x86_64-darwin"; overlays = import ./overlays.nix { use_enterprise = false; pkgs-for-gcloud = import nixpkgs-for-gcloud { system = "x86_64-darwin"; }; }; }
              else oss_pkgs;

        in
        {
          packages = {
            # Forwarded so we can get the path from sbt.
            reredirects = oss_pkgs.python3.pkgs.sphinx-reredirects;
          };
          # For now, the default is enterprise. Use `nix develop path:nix#oss` to use the OSS version.
          devShells.default = import ./shell.nix {
            x86Pkgs = enterprise_x86Pkgs;
            pkgs = enterprise_pkgs;
            variant = "enterprise";
          };
          devShells.oss = import ./shell.nix {
            x86Pkgs = oss_x86Pkgs;
            pkgs = oss_pkgs;
            variant = "oss";
          };
          devShells.static_tests = import ./shell.nix {
            x86Pkgs = oss_x86Pkgs;
            pkgs = oss_pkgs;
            variant = "static_tests";
          };
        }
      );
}
