#!/usr/bin/env python3
"""Fase 0: prueba rápida del cable NexStar desde un portátil.
Uso:  pip install pyserial
      python nexstar_test.py COM3          (Windows)
      python nexstar_test.py /dev/ttyUSB0  (Linux/macOS)
"""
import sys, time, serial

AZM, ALT = 16, 17          # motor controller device ids
POS, NEG = 36, 37          # fixed-rate slew directions

def cmd(s, data, timeout=3.5):
    # Celestron: drivers must wait up to 3.5 s for a hand control response.
    s.reset_input_buffer()
    s.write(data)
    out, t0 = b"", time.time()
    while time.time() - t0 < timeout:
        c = s.read(1)
        if c:
            out += c
            if c == b"#":
                return out
    raise TimeoutError(f"No response to {data!r} (got {out!r})")

def passthrough(s, dev, msg_id, args=b"", resp_len=0):
    """'P' pass-through to a motor controller. Returns the response bytes."""
    # Length byte = msg_id + data bytes; data is padded to 3 bytes.
    out = cmd(s, bytes([ord("P"), 1 + len(args), dev, msg_id]) + args.ljust(3, b"\0") + bytes([resp_len]))
    # On error the HC returns one extra byte before '#'.
    if len(out) != resp_len + 1:
        raise IOError(f"Pass-through error dev={dev} id={msg_id:#x}: {out!r}")
    return out[:-1]

def hex_to_deg(h):
    return int(h, 16) / 2**32 * 360.0

def signed_deg(d):
    # Dec/Alt come back as 0..360; values above 180 are negative angles.
    return d - 360.0 if d > 180.0 else d

def slew(s, axis, direction, rate):
    cmd(s, bytes([ord("P"), 2, axis, direction, rate, 0, 0, 0]))

def stop_all(s):
    for axis in (AZM, ALT):
        try:
            slew(s, axis, POS, 0)
        except Exception as e:
            print(f"WARNING: could not stop axis {axis}: {e}")

def main():
    port = sys.argv[1] if len(sys.argv) > 1 else "/dev/ttyUSB0"
    with serial.Serial(port, 9600, timeout=0.2) as s:
        print("Echo:", cmd(s, b"Kx"))
        v = cmd(s, b"V")
        print(f"HC version: {v[0]}.{v[1]}  (precise RA/Dec needs 1.6+, precise Az/Alt 2.2+)")
        print("Model:", cmd(s, b"m")[0], "(7 = SLT)")
        print("Aligned:", cmd(s, b"J")[0] == 1)
        ra, dec = cmd(s, b"e")[:-1].decode().split(",")
        print(f"RA {hex_to_deg(ra)/15:.4f} h  Dec {signed_deg(hex_to_deg(dec)):+.4f} deg")
        for name, dev in (("AZM", AZM), ("ALT", ALT)):
            try:
                mv = passthrough(s, dev, 0xFE, resp_len=2)
                pb = passthrough(s, dev, 0x40, resp_len=1)[0]
                nb = passthrough(s, dev, 0x41, resp_len=1)[0]
                print(f"{name} motor fw {mv[0]}.{mv[1]}  anti-backlash +{pb} / -{nb}")
            except Exception as e:
                print(f"{name} motor info unavailable: {e}")

        print("Moving azimuth + (rate 5) for 2 s...")
        try:
            slew(s, AZM, POS, 5)
            time.sleep(2)
        finally:
            stop_all(s)   # always stop, even on Ctrl+C or error
        print("Stopped. Cable and protocol OK.")

if __name__ == "__main__":
    main()
