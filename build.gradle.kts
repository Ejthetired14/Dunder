plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
// Debug: Print truststore
tasks.register("printTruststore") {
    doLast {
        println("javax.net.ssl.trustStore: " + System.getProperty("javax.net.ssl.trustStore"))
        println("javax.net.ssl.trustStorePassword: " + System.getProperty("javax.net.ssl.trustStorePassword"))
    }
}