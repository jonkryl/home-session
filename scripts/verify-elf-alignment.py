"""Check 64-bit native PT_LOAD segments support Android's 16 KB page size."""
import struct
import sys
import zipfile

checked = 0
with zipfile.ZipFile(sys.argv[1]) as archive:
    for name in sorted(archive.namelist()):
        if not name.endswith(".so") or not name.startswith(("lib/arm64-v8a/", "lib/x86_64/")):
            continue
        elf = archive.read(name)
        if elf[:4] != b"\x7fELF" or elf[4] != 2:
            raise SystemExit(f"Unexpected native ELF format: {name}")
        endian = "<" if elf[5] == 1 else ">"
        ph_offset = struct.unpack_from(endian + "Q", elf, 32)[0]
        ph_size, ph_count = struct.unpack_from(endian + "HH", elf, 54)
        for index in range(ph_count):
            offset = ph_offset + index * ph_size
            segment_type = struct.unpack_from(endian + "I", elf, offset)[0]
            if segment_type == 1:  # PT_LOAD
                alignment = struct.unpack_from(endian + "Q", elf, offset + 48)[0]
                if alignment < 16_384:
                    raise SystemExit(f"{name} requires a smaller page size: PT_LOAD alignment {alignment}")
        checked += 1
        print(f"16 KB compatible: {name}")
print(f"Verified {checked} 64-bit native libraries")
