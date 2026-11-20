plugins {
    id("com.android.application") version "8.3.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "1.9.20" apply false
    id("com.google.devtools.ksp") version "1.9.20-1.0.13" apply false
}
// Debug: Print truststore
tasks.register("printTruststore") {
    doLast {
        println("javax.net.ssl.trustStore: " + System.getProperty("javax.net.ssl.trustStore"))
        println("javax.net.ssl.trustStorePassword: " + System.getProperty("javax.net.ssl.trustStorePassword"))
    }
}
