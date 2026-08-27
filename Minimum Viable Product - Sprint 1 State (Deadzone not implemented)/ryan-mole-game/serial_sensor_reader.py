import argparse
import re
import sys

import serial


Y_PATTERN = re.compile(r"Y\s*[:=]\s*([-+]?\d+(?:\.\d+)?)", re.IGNORECASE)


def main() -> None:
    parser = argparse.ArgumentParser(description="Read sensor position lines from a serial port and print them")
    parser.add_argument("--port", default="COM3", help="Serial port name, e.g. COM3")
    parser.add_argument("--baud", type=int, default=115200, help="Serial baud rate")
    args = parser.parse_args()

    try:
        with serial.Serial(args.port, args.baud, timeout=0.2) as ser:
            print(f"Listening on {args.port} @ {args.baud} baud...", flush=True)
            while True:
                line = ser.readline().decode(errors="ignore").strip()
                if line:
                    match = Y_PATTERN.search(line)
                    if match:
                        print(f"y:{match.group(1)}", flush=True)
    except KeyboardInterrupt:
        pass
    except Exception as exc:
        print(f"Serial error: {exc}", file=sys.stderr, flush=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
