# Run sbt from the nix store when `direnv exec . sbt` cannot realize the dev shell (root overlay full).
# Source it from the repo root. Store paths are those of the splice-ready sandbox template of 2026-09; if a
# path is missing, `ls -d /nix/store/*-<name>*` and substitute. Prefer the direnv route when it works.
# source me: run sbt from the nix store without realizing the dev shell (pulumi does not fit on /)
cd "$(git rev-parse --show-toplevel)"
export USER=$(id -un)
export JAVA_HOME=/nix/store/lhld7i73l9dd8fi1zlqy977ak70f8d8l-openjdk-21.0.12+2
export PATH="/nix/store/fy07r34xrzcfpghx2bhzdg4fjz2z8p53-sbt-1.12.11/bin:$JAVA_HOME/bin:/nix/store/6hg9859rpsxk5fp3sv0790gvjm619asl-dpm-sdk-3.5.2/bin:/nix/store/89jvmxlcm4zc2m62xs9p2pjihr37c1k5-protobuf-25.9/bin:/nix/store/3bj8sf1k89wpxd0p1rwcbs7kal59kg7j-openapi-generator-cli-6.6.0/bin:/nix/store/2qxdj2gxwckn22v4h78v1ijr4m6swfmj-nodejs-slim-24.15.0/bin:/nix/store/2jsg94lzhjd4s94xs1z37bq5fmgdn5sj-nodejs-slim-24.14.0-npm/bin:/nix/store/5jf55qkwrnd769ri9wxiy7z9kp5zb4ca-jq-1.8.1/bin:/nix/store/9bhf1ff1kwhmyynws5jkjp8k7pkh6kix-coreutils-9.11/bin:$PATH"
set -a; source .envrc.vars 2>/dev/null; set +a
export DPM_SDK_VERSION=3.5.2 DAML_COMPILER_VERSION=3.5.2
export PROTOC=/nix/store/89jvmxlcm4zc2m62xs9p2pjihr37c1k5-protobuf-25.9
export CANTON=/nix/store/kl8xf770xifjcdmzjzqmpryc2dsq5l6p-canton
export CANTON_VERSION=$(python3 -c "import json;print(json.load(open('nix/canton-sources.json'))['version'])")
export LC_ALL=C.UTF-8
mkdir -p log/jtmp log/sbt-boot log/ivy2
export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djava.io.tmpdir=$PWD/log/jtmp"
export SBT_OPTS="$SBT_OPTS -Dsbt.boot.directory=$PWD/log/sbt-boot -Dsbt.ivy.home=$PWD/log/ivy2"
export DPM_HOME=/nix/store/6hg9859rpsxk5fp3sv0790gvjm619asl-dpm-sdk-3.5.2
export PATH="/nix/store/f0y2psw43gfbawfhqlq2rfa718p7cylj-patch-2.8/bin:/nix/store/g3ajbl1clfw1x9xd819fs025chp09b0k-diffutils-3.12/bin:$PATH"
export PATH="/nix/store/mlyqvaa6lcwjfbp1dvzxkd9g46fksdnj-tmux-3.6a/bin:/nix/store/ai0n5cpq9ybjqmhyivv8hzvxbvknb6xl-toxiproxy-2.12.0/bin:$CANTON/bin:$PATH"
export COMETBFT_DRIVER=/nix/store/a08vqck8vvdw9qlzwr7r49nhp38rwldq-cometbft-driver
export COMETBFT_RELEASE_VERSION=3.5.0-snapshot.20260203.17930.0.v8a849517-stable-20260204
