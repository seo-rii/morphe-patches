#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fetch pinned public build dependencies without replacing any existing source."""
import argparse
from pathlib import Path
import subprocess


SOURCES = {
    "morphe-patches-gradle-plugin": "5b7e4619931a8c78b7858e598e002222d37021b9",
    "morphe-patches-library": "11f8c7e4d9dd64e18b0594d2c7cbf09176a65327",
    "morphe-patcher": "d19545e8505e0329cc640723cb8ff91eddd3cd05",
    "ARSCLib": "675bed2de397097400ba6f0c808e28005ae5aa53",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="New directory outside the source checkout")
    args = parser.parse_args()
    directory = args.directory.resolve()
    directory.mkdir(parents=True, exist_ok=False)
    for name, commit in SOURCES.items():
        destination = directory / name
        subprocess.run(["git", "init", "--quiet", str(destination)], check=True)
        subprocess.run(["git", "-C", str(destination), "remote", "add", "origin",
                        f"https://github.com/MorpheApp/{name}.git"], check=True)
        subprocess.run(["git", "-C", str(destination), "fetch", "--depth=1", "origin", commit], check=True)
        subprocess.run(["git", "-C", str(destination), "checkout", "--detach", "FETCH_HEAD"], check=True)
        actual = subprocess.check_output(["git", "-C", str(destination), "rev-parse", "HEAD"], text=True).strip()
        if actual != commit:
            raise RuntimeError(f"Unexpected public source revision: {name}")
    # The released plugin otherwise requires a token during configuration even
    # when all Morphe modules are supplied by public composite builds.
    patch = Path(__file__).resolve().parents[1] / "build-support/wireguard-public-plugin.patch"
    plugin = directory / "morphe-patches-gradle-plugin"
    subprocess.run(["git", "-C", str(plugin), "apply", "--check", str(patch)], check=True)
    subprocess.run(["git", "-C", str(plugin), "apply", str(patch)], check=True)
    print(f"Prepared pinned public sources in {directory}")
    print("No binaries were published and no credentials were used.")


if __name__ == "__main__":
    main()
