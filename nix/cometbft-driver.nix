{ stdenv }:

let sources = builtins.fromJSON (builtins.readFile ./cometbft-driver-sources.json);
in
stdenv.mkDerivation rec {
  name = "cometbft-driver";
  version = sources.version;
  src = builtins.fetchurl {
    url = "https://storage.googleapis.com/da-images-public/canton-drivers/canton-drivers-${version}.tar.gz";
    sha256 = sources.sha256;
  };
  dontUnpack = true;
  installPhase = ''
    mkdir -p $out
    tar -xzf $src
    cp canton-drivers-${sources.version}/lib/canton-drivers-${sources.version}-all.jar $out/driver.jar
  '';
}
