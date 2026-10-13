"""Fake ESP32: sends the same UDP sensor packets the real firmware would.

Lets you test the whole game pipeline (UDP -> parse -> triangulation -> game)
with no hardware. Uses the TABLE_TEST geometry: sensors at x=12.5 and x=37.5
on the wall, 50 cm wide field, boxes at depth 25-50 cm.

Run the game first (java -cp bin whackamole.Main), then in another window:

    py sim_sensor.py

Controls:
    A / D : move the fake player left / right
    W / S : move closer to / further from the sensors
    1     : only sensor 1 echoes (tests the fallback)
    2     : only sensor 2 echoes
    0     : nobody in the field (both "No echo")
    B     : back to both sensors echoing (normal)
    Q     : quit
"""

import math
import socket
import sys
import time

try:
    import msvcrt  # Windows-only keyboard polling
except ImportError:
    msvcrt = None

HOST, PORT = "127.0.0.1", 4210

# TABLE_TEST geometry (cm) - keep in sync with SensorInputBridge
S1_X, S2_X = 12.5, 37.5

x, y = 12.5, 37.5   # fake player position: start centre of Box 1
mode = "both"        # both | s1 | s2 | none
STEP = 1.5

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)


def reading(sensor_x):
    return math.hypot(x - sensor_x, y)


def packet():
    r1 = f"{reading(S1_X):.1f} cm" if mode in ("both", "s1") else "No echo"
    r2 = f"{reading(S2_X):.1f} cm" if mode in ("both", "s2") else "No echo"
    return f"Sensor 1: {r1} Sensor 2: {r2}"


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def main():
    global x, y, mode
    if msvcrt is None:
        print("This controller needs Windows (msvcrt). Sending a fixed sweep instead.")
    print(__doc__)
    print("Sending to %s:%d - move me around and watch the game window." % (HOST, PORT))
    while True:
        if msvcrt is not None:
            while msvcrt.kbhit():
                key = msvcrt.getch().decode(errors="ignore").lower()
                if key == "q":
                    print("bye")
                    return
                elif key == "a":
                    x = clamp(x - STEP, 0, 50)
                elif key == "d":
                    x = clamp(x + STEP, 0, 50)
                elif key == "w":
                    y = clamp(y - STEP, 5, 70)
                elif key == "s":
                    y = clamp(y + STEP, 5, 70)
                elif key == "1":
                    mode = "s1"
                elif key == "2":
                    mode = "s2"
                elif key == "0":
                    mode = "none"
                elif key == "b":
                    mode = "both"
        else:
            # non-Windows fallback: sweep left-right through both boxes
            t = time.time() % 8
            x = 5 + (t / 8) * 40
            y = 37.5
        line = packet()
        sock.sendto(line.encode(), (HOST, PORT))
        box = "Box 1" if x < 25 else "Box 2"
        print(f"\r({x:5.1f},{y:5.1f}) {box:6s} [{mode:4s}] -> {line:55s}", end="", flush=True)
        time.sleep(0.15)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nbye")
