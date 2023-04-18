{
  description = "datalog schema";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/release-22.11";
    nixpkgs-unstable.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    github-linguist.url = "github:slimslenderslacks/linguist";
    clj-nix = {
      url = "github:jlesquembre/clj-nix";
      inputs.flake-utils.follows = "flake-utils";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, nixpkgs-unstable, flake-utils, github-linguist, clj-nix }:

    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
	pkgs-unstable = import nixpkgs-unstable { inherit system; };
	cljpkgs = clj-nix.packages."${system}";
      in
      {
        packages = {
          clj = cljpkgs.mkCljLib {
            projectSrc = ./.;
            name = "docker/datalog-schema";
          };
	};
        devShells.default = pkgs.mkShell {
          name = "nixie";
          packages = with pkgs; [ babashka clojure pkgs.graalvmCEPackages.graalvm17-ce pkgs-unstable.clojure-lsp temurin-bin neovim github-linguist.packages.aarch64-darwin.default ];

          shellHook = ''
            export GRAALVM_HOME=${pkgs.graalvmCEPackages.graalvm17-ce};
          '';
        };
      });
}
