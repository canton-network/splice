#!/usr/bin/env python3
from dockerfile_parse import DockerfileParser
import sys
import subprocess
import json

if (len(sys.argv) != 2):
  print(f"usage: {sys.argv[0]} <dockerfile_filename>")
  sys.exit(1)
filename = sys.argv[1]
print(f"\n\n**** Checking dockerfile: {filename}")
d = DockerfileParser(
  filename,
  build_args={
    "image_sha256": "sha256:ignore-me",
    "base_version": "base@sha256:ignore-me",
    "cometbft_version": "cometbft_version",
    "cometbft_sha": "ignore-me"
    }
  )
parent_images = d.parent_images
for image_name in parent_images:
  print(f"Testing image {image_name}...")
  if not "@sha256:" in image_name:
    print(f"Image {image_name} not pinned by digest")
    sys.exit(1)

  if not "sha256:ignore-me" in image_name:
    print("Good, it's pinned by digest")
    try:
      inspect_str = subprocess.check_output(
              ["docker", "buildx", "imagetools", "inspect", image_name, "--format", "{{ json . }}"],
              stderr=subprocess.STDOUT,
              text=True
          )
    except subprocess.CalledProcessError as e:
      print(f"Failed to inspect image {image_name}: {e.output}")
      sys.exit(1)

    inspect = json.loads(inspect_str)
    if not 'manifests' in inspect['manifest']:
      print(f"Image {image_name} not a multi-arch image")
      sys.exit(1)
    archs = [m['platform']['architecture'] for m in inspect['manifest']['manifests']]
    if not 'amd64' in archs:
      print(f"Image {image_name} supports only architectures {archs}, not amd64")
      sys.exit(1)
    if not 'arm64' in archs:
      print(f"Image {image_name} supports only architectures {archs}, not arm64")
      sys.exit(1)
    print("Good, it's multi-arch, and supports amd64 & arm64")
