plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

val protocolDoc = rootProject.file("docs/protocol.md")

tasks.test {
    maxHeapSize = "512m"
    // The tests parse the worked examples and tables in the protocol document.
    inputs.file(protocolDoc)
    systemProperty("velocast.protocolDoc", protocolDoc.absolutePath)
}
