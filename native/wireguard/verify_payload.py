#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Check the Android ELF payload independently of a successful compiler exit."""

import argparse
from pathlib import Path
import struct


def verify(abi: str, data: bytes) -> None:
    if abi not in ("arm64-v8a", "x86_64"):
        raise ValueError("Unsupported Android ABI")
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Payload must be a little-endian ELF64 library")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    if elf_type != 3 or machine != {"arm64-v8a": 183, "x86_64": 62}[abi]:
        raise ValueError("Payload must be a shared library for the requested ABI")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phcount = struct.unpack_from("<HH", data, 54)
    if phsize != 56 or not phcount or phoff + phsize * phcount > len(data):
        raise ValueError("Invalid ELF program header table")
    headers = [struct.unpack_from("<IIQQQQQQ", data, phoff + i * phsize)
               for i in range(phcount)]
    loads = [header for header in headers if header[0] == 1]
    if not loads or any(header[7] < 16384 or header[7] & (header[7] - 1)
                        or header[2] % header[7] != header[3] % header[7]
                        for header in loads):
        raise ValueError("ELF LOAD segments must support 16 KiB Android pages")
    if any(header[1] & 3 == 3 for header in loads):
        raise ValueError("ELF contains a writable executable segment")
    stacks = [header for header in headers if header[0] == 0x6474E551]
    if len(stacks) != 1 or stacks[0][1] & 1:
        raise ValueError("ELF must declare a non-executable stack")
    if not any(header[0] == 0x6474E552 for header in headers):
        raise ValueError("ELF must include GNU_RELRO")
    dynamic = [header for header in headers if header[0] == 2]
    if len(dynamic) != 1:
        raise ValueError("ELF must contain a dynamic table")
    offset, length = dynamic[0][2], dynamic[0][5]
    if length % 16 or offset + length > len(data):
        raise ValueError("Invalid ELF dynamic table")
    entries = [struct.unpack_from("<qQ", data, i)
               for i in range(offset, offset + length, 16)]
    # DT_BIND_NOW, DF_BIND_NOW, or DF_1_NOW.
    if not any(tag == 24 or tag == 30 and value & 8 or tag == 0x6FFFFFFB and value & 1
               for tag, value in entries):
        raise ValueError("ELF must resolve dynamic relocations at load time")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("abi", choices=("arm64-v8a", "x86_64"))
    parser.add_argument("library", type=Path)
    args = parser.parse_args()
    try:
        verify(args.abi, args.library.read_bytes())
    except (ValueError, OSError, struct.error) as error:
        parser.exit(1, f"Android ELF verification failed: {error}\n")
    print(f"Android ELF verified: {args.abi}, ELF64, 16 KiB pages, NX stack, RELRO/NOW")
