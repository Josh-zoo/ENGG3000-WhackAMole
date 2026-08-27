# Whack-a-Mole (Java / Swing)

A simple, dependency-free Whack-a-Mole game. Moles pop up in a 3x3 grid of
holes at random intervals; each stays up for a random amount of time. **A
point is scored if the cursor is hovering over a mole at the instant its
timer runs out** (no click needed).

## How to build & run

Requires a JDK (17+ is fine; written/tested logic against 21).

```bash
cd whackamole/src
javac whackamole/*.java
java whackamole.Main
```
Or you can just run Main.java in VSCode.
