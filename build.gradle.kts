plugins {
    // AGP 8.7.x requires Gradle 8.9+ - matches the wrapper version pinned
    // in gradle/wrapper/gradle-wrapper.properties. Keep these two in lockstep;
    // bumping one without the other is the single most common reason a
    // freshly cloned Android project fails to sync.
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
