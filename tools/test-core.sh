#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .local/core-tests
javac --release 17 -d .local/core-tests app/src/main/java/dev/aura/player/Lrc.java app/src/main/java/dev/aura/player/KaraokeTiming.java app/src/test/java/dev/aura/player/KaraokeTimingTest.java app/src/main/java/dev/aura/player/Hex.java app/src/test/java/dev/aura/player/CoreTest.java app/src/main/java/dev/aura/player/RemoteServer.java app/src/test/java/dev/aura/player/RemoteTest.java
java -cp .local/core-tests dev.aura.player.CoreTest
java -cp .local/core-tests dev.aura.player.KaraokeTimingTest
java -Dsun.net.http.allowRestrictedHeaders=true -cp .local/core-tests dev.aura.player.RemoteTest
