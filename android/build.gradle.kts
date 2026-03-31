plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
