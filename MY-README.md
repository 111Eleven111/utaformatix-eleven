# Building and running from VSCode

- Full build (may run JS browser tests which need Chrome):

export JAVA_HOME=$(/usr/libexec/java_home -v 11)
./gradlew build --no-daemon --stacktrace

- If you want to skip the JS browser tests (common when building locally without Chrome or CI):

export JAVA_HOME=$(/usr/libexec/java_home -v 11)
./gradlew build -x jsBrowserTest --no-daemon --stacktrace

- Build just the production JS bundle (output under build/):

export JAVA_HOME=$(/usr/libexec/java_home -v 11)
./gradlew jsBrowserProductionWebpack -x jsBrowserTest

- Run the development server (hot reload / dev webpack),
then open http://localhost:33221 (port is set in the build script)

export JAVA_HOME=$(/usr/libexec/java_home -v 11)
./gradlew jsBrowserDevelopmentRun -x jsBrowserTest

- If you run into cached or resolution problems, refresh dependencies:

./gradlew --refresh-dependencies build -x jsBrowserTest

# TODOs

At this point, project builds and runs in dev server. Running dev server might be stuck at 96% in terminal but the localhost window does seem to show and work, needs futher inspection maybe.
TODO next:

1. Check that it converts files correctly from svp to ust in this state
2. Implement and add .ppsf(NT) export support