#!/usr/bin/env python3
"""Unblock Rosetta's blocking FIFO open, handing off to Ninja's real writer.

No progress bytes are generated. Release each temporary writer once Ninja opens
its own write descriptor, so the actual reader still receives EOF at Ninja exit.
Only used inside the isolated Linux builder, never on an Android device.
"""
import os
import pathlib
import signal
import stat
import sys
import time


def real_writer(inode, own_pid):
    for process in pathlib.Path('/proc').iterdir():
        if not process.name.isdigit() or int(process.name) == own_pid:
            continue
        try:
            for descriptor in (process / 'fd').iterdir():
                try:
                    if descriptor.stat().st_ino != inode or not stat.S_ISFIFO(descriptor.stat().st_mode):
                        continue
                    fields = (process / 'fdinfo' / descriptor.name).read_text().splitlines()
                    flags = next(int(line.split()[1], 8) for line in fields if line.startswith('flags:'))
                    if flags & os.O_ACCMODE in (os.O_WRONLY, os.O_RDWR):
                        return True
                except (OSError, StopIteration):
                    continue
        except OSError:
            continue
    return False


def main():
    fifo = pathlib.Path(sys.argv[1])
    parent = int(sys.argv[2])
    held, handed_off = {}, set()
    running = True

    def stop(*_):
        nonlocal running
        running = False

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        while running:
            try:
                os.kill(parent, 0)
            except ProcessLookupError:
                break
            try:
                metadata = fifo.stat()
                inode = metadata.st_ino
                if stat.S_ISFIFO(metadata.st_mode) and inode not in held and inode not in handed_off:
                    descriptor = os.open(fifo, os.O_RDWR | os.O_NONBLOCK)
                    opened = os.fstat(descriptor)
                    if opened.st_ino == inode and stat.S_ISFIFO(opened.st_mode):
                        held[inode] = descriptor
                        print('Opened temporary Ninja FIFO endpoint', flush=True)
                    else:
                        os.close(descriptor)
            except FileNotFoundError:
                pass
            for inode, descriptor in list(held.items()):
                if real_writer(inode, os.getpid()):
                    os.close(descriptor)
                    del held[inode]
                    handed_off.add(inode)
                    print('Handed FIFO to the actual Ninja writer', flush=True)
            time.sleep(0.05)
    finally:
        for descriptor in held.values():
            os.close(descriptor)


if __name__ == '__main__':
    main()
