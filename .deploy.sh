

echo "deploy"
./gradlew build

# Jar filename is derived from pom.xml's <version> rather than hardcoded, so this script
# doesn't need a manual edit every time the version is bumped.

VERSION=$(./gradlew properties -q | grep "^version:" | awk '{print $2}')
JAR_FILE="build/libs/springchat3-${VERSION}.jar"
echo "cp jar file to opt/springchat3 folder"
sudo cp "$JAR_FILE" /opt/springchat3

# The LaunchDaemon plist hardcodes the jar path it launches (ProgramArguments: -jar <path>), so
# copying a new-version jar above does nothing for the running service unless this path also
# gets updated to match. Check it, and fix it in place if it's still pointing at a different
# (older) version's jar, before the bootout/bootstrap below picks the plist back up.
PLIST="/Library/LaunchDaemons/ch.arcticsoft.springchat3.plist"
EXPECTED_JAR="/opt/springchat3/springchat3-${VERSION}.jar"

if ! sudo test -f "$PLIST"; then
    echo "ERROR: $PLIST not found — can't verify the jar it launches." >&2
    exit 1
fi

if sudo grep -qF "$EXPECTED_JAR" "$PLIST"; then
    echo "$PLIST already points at $EXPECTED_JAR"
else
    echo "$PLIST points at a different jar — updating it to $EXPECTED_JAR"
    sudo sed -i '' -E "s#/opt/springchat3/springchat3-[^<]*\.jar#${EXPECTED_JAR}#" "$PLIST"
fi

echo "bootout..."
sudo launchctl bootout system /Library/LaunchDaemons/ch.arcticsoft.springchat3.plist
echo "bootstrap..."
sudo launchctl bootstrap system /Library/LaunchDaemons/ch.arcticsoft.springchat3.plist
