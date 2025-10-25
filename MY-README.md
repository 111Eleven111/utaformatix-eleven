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

- auto formating with ktlint

export JAVA_HOME=$(/usr/libexec/java_home -v 11) && ./gradlew :core:ktlintFormat --no-daemon --stacktrace

# TODOs

At this point, project builds and runs in dev server. Running dev server might be stuck at 96% in terminal but the localhost window does seem to show and work, needs futher inspection maybe. Edit: seems to work fine regardles.

1. Check that it converts files correctly from ust to svp in this state, DONE / CHEKED 21.10.25
2. Implement and add .ppsf(NT) export support

Update 25.10.25:
test4converted.ppsf now successfully imports in piapro without the error=7! However the notes seem to not read properly as when opened in piapro studio, only one note is displayed in the piano roll, but multiple notes are heard and seen in the bottom audio section, and changing or adding notes causes a crash, i think because of faulty conversion.

I have made two new files in the format-comparisons folder in temp-export-tests. Here the .svp file is created with five notes, and the .ppsf file is created with piapro and has the same notes. The goal is to make the converter be able to take svp files and convert them to ppsf files and succsefully carry over note information properly!

Currently when trying to convert the melodyTest from synth to ppsf, i press play and can hear that more notes are being sung, but the piano roll editor only displayes one "ra", and adding a note crashes the piapro, so while yes the files opens up, (probably due to staying really close to the template) conversion is not sucesful as note information is not carried over properly. How would i start fixing this?

melodyTestConvertedFromSVPToPiaproTest.ppsf

is the file that is a convertion attemt on melodyTestSynthVCreatedFile.svp

We want to debug so that the program can import any .svp file and carry over note information to a .ppsf file.

Update, tried fixing to note convertinon issue
Note convertion still broken. New E99 issue.
Now opening converted files causes (E99):
Failed to load Piapro Studio.
invalid vector subscript

