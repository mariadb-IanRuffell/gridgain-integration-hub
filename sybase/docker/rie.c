/*
 * LD_PRELOAD shim that makes SAP ASE 16.0 SP02 start on Linux >= 5.8.
 *
 * The dataserver binary asks for an executable stack (PT_GNU_STACK = RWE) and runs code from readable heap memory
 * (Snap::Validate during dsinit). Before Linux 5.8 the kernel gave such 64-bit binaries READ_IMPLIES_EXEC
 * automatically; since 5.8 it does not, and ASE dies with SIGSEGV (instruction fetch from a non-executable page).
 * The personality flag cannot be set from outside (the kernel clears it on every exec of a 64-bit binary) but a
 * process may set it on itself, and it then applies to every later mmap/mprotect, including glibc's arenas.
 */
#include <sys/personality.h>

__attribute__((constructor(101)))
static void enable_read_implies_exec(void)
{
    int p = personality(0xffffffff);          /* query the current persona */
    if (p != -1)
        personality(p | READ_IMPLIES_EXEC);
}
