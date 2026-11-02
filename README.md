# Client Vein Miner

A client-side-only Fabric mod for Minecraft 26.3. Hold the Vein Miner key (default: `` ` ``) while mining an ore or log
and connected blocks of the same type are mined too. The server does not need the mod: it only sees normal mining.

## Use
1. Install Fabric Loader and Fabric API for 26.3.
2. Drop the mod jar in `mods/`.
3. Hold the key and mine an ore or log. Rebind the key in Controls.

## Notes
- Max vein size is 64. Blocks out of reach are skipped, and it stops before your tool breaks.
- Many multiplayer servers ban this kind of automation. Check the rules before using it on someone else's server.

## Build
```
gradle wrapper      # one time, needs Gradle 9.x installed
./gradlew build     # jar ends up in build/libs/
```
Requires JDK 25.
