#!/bin/bash
libs=$(find libs/ -name '*.jar' -exec echo -n :{} \;)
libs=$libs:$(find . -maxdepth 1 -name '*.jar' -exec echo -n :{} \;)
libs=${libs:1}

java -cp "$libs" org.asf.centuria.launcher.updater.LauncherUpdaterMain "$@" &
