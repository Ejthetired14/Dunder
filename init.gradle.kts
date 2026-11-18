import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore

// Configure SSL to trust all certificates (for development only)
System.setProperty("javax.net.ssl.trustStore", "/etc/ssl/certs/java/cacerts")
System.setProperty("javax.net.ssl.trustStorePassword", "changeit")

// Print truststore info
println("INIT: javax.net.ssl.trustStore = " + System.getProperty("javax.net.ssl.trustStore"))
println("INIT: javax.net.ssl.trustStorePassword = " + System.getProperty("javax.net.ssl.trustStorePassword"))

// Force Gradle to use system truststore for all HTTP connections
val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
val ks = KeyStore.getInstance("JKS")
ks.load(java.io.FileInputStream("/etc/ssl/certs/java/cacerts"), "changeit".toCharArray())
tmf.init(ks)
val sslContext = SSLContext.getInstance("TLS")
sslContext.init(null, tmf.trustManagers, null)
SSLContext.setDefault(sslContext)

println("INIT: Custom SSLContext set as default")
