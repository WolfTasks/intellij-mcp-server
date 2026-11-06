// Gradle init script used only by the nightly security workflow.
// Trivy can only see Gradle dependencies through a gradle.lockfile, which this
// project does not commit. This activates dependency locking ephemerally for the
// classpaths that end up in (or compile against) the shipped plugin, so that
// `./gradlew dependencies --write-locks` produces a lockfile Trivy can scan.
//
// The IntelliJ Platform SDK (intellijIdeaCommunity, bundled plugins) is resolved
// through the intellijPlatform* configurations and is deliberately NOT locked:
// it is provided by the user's IDE at runtime and is not shipped in the plugin ZIP.
allprojects {
    configurations.matching { it.name in setOf("runtimeClasspath", "compileClasspath") }.configureEach {
        resolutionStrategy.activateDependencyLocking()
    }
}
