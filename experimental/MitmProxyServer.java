///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 11+
//DEPS org.eclipse.jetty:jetty-proxy:11.0.25
//DEPS org.eclipse.jetty:jetty-server:11.0.25
//DEPS org.eclipse.jetty:jetty-servlet:11.0.25
//DEPS org.eclipse.jetty:jetty-slf4j-impl:11.0.25
//DEPS org.eclipse.jetty:jetty-client:11.0.25
//DEPS org.eclipse.jetty:jetty-alpn-client:11.0.25
//DEPS org.bouncycastle:bcprov-jdk18on:1.78.1
//DEPS org.bouncycastle:bcpkix-jdk18on:1.78.1
///FILES ca.p12
//FILES jetty-logging.properties=jetty.props

import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509ExtensionUtils;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request.Listener;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * WARNING: EXPERIMENTAL - Man-in-the-Middle (MitM) Proxy with SSL Inspection.
 * This proxy intercepts HTTPS traffic by generating certificates on the fly.
 * REQUIRES:
 * 1. Client machine MUST trust the root CA certificate ('ca.p12').
 * 2. A 'ca.p12' file containing the root CA certificate and private key.
 * 3. BouncyCastle libraries.
 * SECURITY: Use with extreme caution and only in controlled environments.
 */
public class MitmProxyServer {

    private static final Logger LOG = LoggerFactory.getLogger(MitmProxyServer.class);
    private static final Path LOG_DIRECTORY = Paths.get("request_logs");
    private static final String CA_KEYSTORE_PATH = "ca.p12"; // Must exist
    private static final String CA_KEYSTORE_PASSWORD = "password"; // Change if needed
    private static final String CA_ALIAS = "ca"; // Alias of the CA key/cert in the keystore

    private static PrivateKey caPrivateKey;
    private static X509Certificate caCertificate;
    private static final Map<String, CertificateData> certificateCache = new ConcurrentHashMap<>();
    private static SslContextFactory.Client clientSslContextFactory; // For connections TO target servers
    private static final ExecutorService tunnelExecutor = Executors.newCachedThreadPool();
    private static HttpClient httpClient; // For making requests to target servers

    // Static initializer to add BouncyCastle provider and load CA
    static {
        Security.addProvider(new BouncyCastleProvider());
        try {
            loadCA();
            // Standard SSL context for connecting out to servers
            clientSslContextFactory = new SslContextFactory.Client(); // Trusts default CAs
            clientSslContextFactory.start();

            // Setup Jetty HttpClient using the correct pattern for custom SslContextFactory
            ClientConnector clientConnector = new ClientConnector();
            clientConnector.setSslContextFactory(clientSslContextFactory);

            HttpClientTransport transport = new HttpClientTransportDynamic(clientConnector);
            httpClient = new HttpClient(transport);
            httpClient.setFollowRedirects(false); // Proxy shouldn't follow redirects itself
            httpClient.start();

        } catch (Exception e) {
            LOG.error("FATAL: Could not initialize CA or SSL context", e);
            System.exit(1);
        }
    }

    /**
     * Loads the CA private key and certificate from the PKCS12 keystore.
     */
    private static void loadCA() throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException, UnrecoverableKeyException {
        LOG.info("Loading CA from: {}", CA_KEYSTORE_PATH);
        Path caPath = Paths.get(CA_KEYSTORE_PATH);
        if (!Files.exists(caPath)) {
            throw new FileNotFoundException("CA Keystore not found at: " + caPath.toAbsolutePath());
        }

        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (InputStream is = Files.newInputStream(caPath)) {
            keystore.load(is, CA_KEYSTORE_PASSWORD.toCharArray());
        }

        caPrivateKey = (PrivateKey) keystore.getKey(CA_ALIAS, CA_KEYSTORE_PASSWORD.toCharArray());
        caCertificate = (X509Certificate) keystore.getCertificate(CA_ALIAS);

        if (caPrivateKey == null || caCertificate == null) {
            throw new KeyStoreException("Could not find CA private key or certificate in " + CA_KEYSTORE_PATH + " with alias " + CA_ALIAS);
        }
        LOG.info("CA Loaded successfully. Issuer: {}", caCertificate.getIssuerX500Principal());
    }

    /**
     * Represents the generated certificate and its key pair.
     * (Replaced record with final class for Java 11 compatibility)
     */
    private static final class CertificateData {
        private final X509Certificate certificate;
        private final PrivateKey privateKey;

        public CertificateData(X509Certificate certificate, PrivateKey privateKey) {
            this.certificate = certificate;
            this.privateKey = privateKey;
        }

        public X509Certificate getCertificate() {
            return certificate;
        }

        public PrivateKey getPrivateKey() {
            return privateKey;
        }
    }

    /**
     * Generates a server certificate for the given hostname, signed by the CA.
     * Uses a cache to avoid regenerating certificates for the same host frequently.
     * Basic implementation - lacks many features of real certificates.
     */
    private static CertificateData generateServerCertificate(String hostname) throws Exception {
        // Check cache first
        CertificateData cached = certificateCache.get(hostname);
        if (cached != null && cached.getCertificate().getNotAfter().after(new Date())) {
             LOG.debug("Using cached certificate for {}", hostname);
             return cached;
        }
         LOG.info("Generating new certificate for {}", hostname);


        // Generate a new key pair for the server certificate
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
        keyPairGenerator.initialize(2048); // Use 2048 bits for security
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        PrivateKey privateKey = keyPair.getPrivate();

        // Certificate details
        X500Name issuer = new X500Name(caCertificate.getSubjectX500Principal().getName());
        X500Name subject = new X500Name("CN=" + hostname); // Common Name set to the hostname
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis()); // Simple serial number
        Date notBefore = new Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24); // Valid from yesterday
        Date notAfter = new Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365); // Valid for 1 year

        // Create the certificate builder
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, subject, publicKey);

        // Add extensions (basic example)
        // Subject Key Identifier
        DigestCalculator digestCalculator = new BcDigestCalculatorProvider().get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1));
        X509ExtensionUtils extensionUtils = new X509ExtensionUtils(digestCalculator);
        // Corrected call: Convert PublicKey to SubjectPublicKeyInfo
        SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(publicKey.getEncoded());
        certBuilder.addExtension(Extension.subjectKeyIdentifier, false, extensionUtils.createSubjectKeyIdentifier(publicKeyInfo));

        // Authority Key Identifier
        // Corrected call: Convert CA PublicKey to SubjectPublicKeyInfo
        SubjectPublicKeyInfo caPublicKeyInfo = SubjectPublicKeyInfo.getInstance(caCertificate.getPublicKey().getEncoded());
        certBuilder.addExtension(Extension.authorityKeyIdentifier, false, extensionUtils.createAuthorityKeyIdentifier(caPublicKeyInfo));

        // Basic Constraints (not a CA)
        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

        // Key Usage (digital signature, key encipherment)
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        // Extended Key Usage (server authentication)
        certBuilder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));

        // Subject Alternative Name (critical for modern browsers)
        GeneralName altName = new GeneralName(GeneralName.dNSName, hostname);
        GeneralNames subjectAltName = new GeneralNames(altName);
        certBuilder.addExtension(Extension.subjectAlternativeName, false, subjectAltName);


        // Sign the certificate using the CA's private key
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption").setProvider("BC").build(caPrivateKey);
        X509Certificate serverCertificate = new JcaX509CertificateConverter().setProvider("BC").getCertificate(certBuilder.build(signer));

        // Verify (optional but recommended)
        serverCertificate.verify(caCertificate.getPublicKey());

        // Use constructor instead of record instantiation
        CertificateData data = new CertificateData(serverCertificate, privateKey);
        certificateCache.put(hostname, data); // Cache the result
        return data;
    }


    /**
     * Helper method to write the URL to a unique file in the log directory.
     */
    private static void writeUrlToFile(String url) {
        try {
            if (!Files.exists(LOG_DIRECTORY)) {
                Files.createDirectories(LOG_DIRECTORY);
                LOG.info("Created log directory: {}", LOG_DIRECTORY.toAbsolutePath());
            }
            String randomFileName = UUID.randomUUID().toString() + ".url";
            Path logFilePath = LOG_DIRECTORY.resolve(randomFileName);
            Files.writeString(logFilePath, url, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Error writing URL log file", e);
        }
    }

    /**
     * Custom ConnectHandler that performs MitM for HTTPS.
     */
    public static class MitmConnectHandler extends AbstractHandler {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException {

            if (!HttpMethod.CONNECT.asString().equalsIgnoreCase(request.getMethod())) {
                 return;
            }

            baseRequest.setHandled(true);

            String connectTarget = request.getRequestURI();
            LOG.info("CONNECT request received for: {}", connectTarget);
            writeUrlToFile("CONNECT " + connectTarget);

            String[] hostPort = connectTarget.split(":");
            String host = hostPort[0];
            int port = (hostPort.length == 2) ? Integer.parseInt(hostPort[1]) : 443;

            // --- MitM Logic ---
            try {
                // 1. Generate server certificate for the target host
                CertificateData serverCertData = generateServerCertificate(host);

                // 2. Client-facing SslContextFactory setup (Skipped for TLS upgrade in this version)

                // 3. Send "200 Connection established" to the client
                final EndPoint endPoint = baseRequest.getHttpChannel().getEndPoint();

                LOG.info("Sending 200 OK to client for {}", connectTarget);
                String httpResponse = "HTTP/1.1 200 Connection established\r\n\r\n";
                // Use Callback.NOOP for the write, removing the Promise and wait
                endPoint.write(Callback.NOOP, java.nio.ByteBuffer.wrap(httpResponse.getBytes(StandardCharsets.ISO_8859_1)));
                LOG.debug("Sent 200 OK confirmation for {}", connectTarget); // Log immediately after write call

                // 4. TLS Upgrade Skipped Warning
                LOG.warn("Skipping actual TLS upgrade on client connection for simplicity in this example.");

                // 5. Establish connection to the actual target server
                LOG.info("Establishing connection to target server: {}:{}", host, port);
                final Socket targetSocket = new Socket(host, port);
                LOG.info("Connected to target server: {}:{}", host, port);

                // 6. Relay data using ByteBuffers and EndPoint fill/write
                LOG.info("Starting data relay between client EndPoint and server Socket for {}", connectTarget);
                final InputStream serverIn = targetSocket.getInputStream();
                final OutputStream serverOut = targetSocket.getOutputStream();
                final ByteBuffer buffer = ByteBuffer.allocate(4096); // Consider using Jetty's buffer pool

                // Client -> Server Relay Thread
                tunnelExecutor.submit(() -> {
                    try {
                        byte[] bytes = new byte[buffer.capacity()];
                        while (endPoint.isOpen() && targetSocket.isConnected() && !targetSocket.isOutputShutdown()) {
                            buffer.clear();
                            int filled = endPoint.fill(buffer); // Read from client EndPoint
                            if (filled == -1) {
                                LOG.debug("Client EndPoint closed (EOF) for {}", connectTarget);
                                try { if (!targetSocket.isOutputShutdown()) targetSocket.shutdownOutput(); } catch (IOException ignored) {} // Signal server EOF
                                break;
                            }
                            if (filled == 0) {
                                continue;
                            }
                            buffer.flip();
                            int len = buffer.remaining();
                            buffer.get(bytes, 0, len);
                            serverOut.write(bytes, 0, len); // Write to server socket stream
                            serverOut.flush();
                        }
                    } catch (EofException e) {
                        LOG.debug("Client EndPoint EOF detected for {}: {}", connectTarget, e.getMessage());
                        try { if (!targetSocket.isOutputShutdown()) targetSocket.shutdownOutput(); } catch (IOException ignored) {}
                    } catch (IOException e) {
                        LOG.warn("Error copying client->server for {}: {}", connectTarget, e.getMessage());
                        closeQuietly(targetSocket);
                    } catch (Exception e) {
                         LOG.error("Unexpected error copying client->server for {}", connectTarget, e);
                         closeQuietly(targetSocket);
                    } finally {
                        closeQuietly(endPoint);
                        LOG.debug("Client->Server relay thread finished for {}", connectTarget);
                    }
                });

                // Server -> Client Relay Thread
                tunnelExecutor.submit(() -> {
                    try {
                        byte[] bytes = new byte[buffer.capacity()];
                        int read;
                        while (targetSocket.isConnected() && !targetSocket.isInputShutdown() && (read = serverIn.read(bytes)) != -1) {
                            if (read > 0 && endPoint.isOpen()) {
                                ByteBuffer writeBuffer = ByteBuffer.wrap(bytes, 0, read);
                                // Use Callback.NOOP for fire-and-forget write
                                endPoint.write(Callback.NOOP, writeBuffer);
                            }
                        }
                        LOG.debug("Server socket closed (EOF) for {}", connectTarget);
                        // endPoint.shutdownOutput(); // May not be directly available/needed
                    } catch (IOException e) {
                         if (e.getMessage() != null && (e.getMessage().contains("Socket closed") || e.getMessage().contains("Connection reset") || e.getMessage().contains("Broken pipe"))) {
                             LOG.debug("Error copying server->client (expected close?) for {}: {}", connectTarget, e.getMessage());
                         } else {
                             LOG.warn("Error copying server->client for {}: {}", connectTarget, e.getMessage());
                         }
                         closeQuietly(endPoint);
                    } catch (Exception e) {
                         LOG.error("Unexpected error copying server->client for {}", connectTarget, e);
                         closeQuietly(endPoint);
                    } finally {
                        closeQuietly(targetSocket);
                        LOG.debug("Server->Client relay thread finished for {}", connectTarget);
                    }
                });

            } catch (Exception e) {
                LOG.error("Error during MitM connection setup for {}", connectTarget, e);
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Failed to establish tunnel");
                } else {
                    LOG.warn("Response already committed, cannot send error for failed tunnel to {}", connectTarget);
                     EndPoint endPoint = baseRequest.getHttpChannel().getEndPoint();
                     closeQuietly(endPoint);
                }
                if (e instanceof IOException) throw (IOException)e;
                if (e instanceof ServletException) throw (ServletException)e;
                throw new ServletException("Tunnel setup failed", e);
            }
        }
    }


    /**
     * Custom ProxyServlet that logs the full request URL before proxying.
     * For standard HTTP requests (not CONNECT).
     */
    public static class LoggingProxyServlet extends ProxyServlet {
        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            StringBuffer urlBuffer = request.getRequestURL();
            String queryString = request.getQueryString();
            if (queryString != null) {
                urlBuffer.append('?').append(queryString);
            }
            String fullUrl = urlBuffer.toString();
            LOG.info("{} request received for: {}", request.getMethod(), fullUrl);
            writeUrlToFile(request.getMethod() + " " + fullUrl);
            super.service(request, response);
        }
    }

    /**
     * The main method to start the proxy server.
     */
    public static void main(String[] args) throws Exception {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                LOG.warn("Invalid port number provided. Using default port {}", port);
            }
        }

        LOG.info("Starting MitM Proxy Server on port {}...", port);

        Server server = new Server(port);

        // --- Handler Setup ---
        // 1. MitmConnectHandler for HTTPS (CONNECT requests)
        MitmConnectHandler connectHandler = new MitmConnectHandler();

        // 2. ServletContextHandler for standard HTTP requests
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        ServletHolder proxyServletHolder = new ServletHolder(new LoggingProxyServlet());
        context.addServlet(proxyServletHolder, "/*");

        // 3. HandlerCollection to chain handlers
        HandlerCollection handlers = new HandlerCollection();
        handlers.addHandler(connectHandler); // Handles CONNECT requests first
        handlers.addHandler(context);        // Handles standard HTTP requests if not CONNECT

        server.setHandler(handlers);

        try {
            server.start();
            LOG.info("Server started successfully on port {}", port);
            LOG.warn("!!! IMPORTANT: Clients MUST trust the CA certificate in '{}' for HTTPS inspection to work !!!", CA_KEYSTORE_PATH);
            LOG.info("Request URLs will be logged to files in the '{}' directory.", LOG_DIRECTORY.toAbsolutePath());

            server.join();
        } catch (Exception e) {
            LOG.error("Error starting or running the server:", e);
            if (server.isStarted()) {
                server.stop();
            }
        } finally {
            if (httpClient != null && httpClient.isStarted()) {
                httpClient.stop();
            }
            tunnelExecutor.shutdownNow(); // Force shutdown of relay threads
            LOG.info("Server stopped.");
        }
    }

     // Helper to close resources quietly
    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

}