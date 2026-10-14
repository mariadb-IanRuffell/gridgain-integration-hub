# Running SAP ASE 16 (datagrip/sybase) in Docker

## Summary

| Where | Result |
|---|---|
| Native x86_64 Linux, kernel >= 5.8 (e.g. llama, kernel 7.0) | **Works** with `rie-x86_64.so` (see `docker-compose.yml`) |
| Native x86_64 Linux, kernel <= 5.4 | Works with no shim (this is why the Ubuntu 20.04 VM on llama worked) |
| Docker Desktop on Apple Silicon, Rosetta | Crashes at startup, shim does not help |
| Docker Desktop on Apple Silicon, QEMU user-mode | Crashes at startup, shim does not help (and QEMU has no kernel AIO) |

## Root cause

The `dataserver` binary (ASE 16.0 SP02 PL02, built 2015) declares an executable stack (`PT_GNU_STACK = RWE`) and, during
`dsinit`, `Snap::Validate()` executes code from readable heap memory. Linux before 5.8 gave such 64-bit binaries
`READ_IMPLIES_EXEC` automatically, so every readable mapping was also executable. Since 5.8 it does not, and the
process dies with SIGSEGV (`ERR 0x15`: user-mode instruction fetch from a present, non-executable page). It is not
emulation-specific: the same image crashes on a native kernel 7.0.

Things that do **not** work: `setarch -X` (the kernel clears `READ_IMPLIES_EXEC` on every exec of a 64-bit binary),
disabling ASLR, `--privileged`, relaxing apparmor/ulimits.

## The fix

`rie.c` is a tiny `LD_PRELOAD` library whose constructor calls `personality(... | READ_IMPLIES_EXEC)` on the process itself
before `main`; the flag then applies to every later `mmap`/`mprotect`, including glibc's malloc arenas. The container
needs `seccomp=unconfined` because Docker's default profile blocks that personality value.

```bash
gcc -O2 -fPIC -shared -o rie-x86_64.so rie.c     # built on llama; needs only the ancient GLIBC_2.2.5 personality symbol
```

## Why it cannot run on the Mac

Rosetta and QEMU user-mode translate x86 code and enforce executable permissions in their own page tables; they ignore
the Linux personality flag. In QEMU the shim loads and the `personality` calls succeed, yet `SnapValidation` still
faults. QEMU user-mode additionally lacks kernel AIO (`io_setup`), so ASE needs `allow sql server async i/o = 0`
just to boot, and `qemu-x86_64` silently drops a `-d<path>` argument (workaround: a `d_master` symlink in the
working directory). Getting past the SnapValidation fault would need a patched vendor binary, a custom allocator,
or full-system x86 emulation without KVM (very slow); none was attempted.

## Image quirks (any host)

- The image's own sample-schema step prints `Incorrect syntax near 'tester'`; harmless, `testdb` and the login exist.
- Port 5000 is taken by macOS AirPlay Receiver on a Mac, so map another host port there.
