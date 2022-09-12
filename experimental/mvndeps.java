///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.apache.maven:maven-model:3.8.6
//DEPS org.apache.maven:maven-settings:3.8.6
//DEPS org.apache.maven:maven-settings-builder:3.8.6
//DEPS org.apache.maven:maven-resolver-provider:3.8.6
//DEPS org.apache.maven.resolver:maven-resolver-api:1.8.2
//DEPS org.apache.maven.resolver:maven-resolver-spi:1.8.2
//DEPS org.apache.maven.resolver:maven-resolver-impl:1.8.2
//DEPS org.apache.maven.resolver:maven-resolver-connector-basic:1.8.2
//DEPS org.apache.maven.resolver:maven-resolver-transport-file:1.8.2
//DEPS org.apache.maven.resolver:maven-resolver-transport-http:1.8.2

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Activation;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Repository;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilder;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultAuthenticationSelector;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;
import org.eclipse.aether.util.repository.DefaultProxySelector;

public class mvndeps {
    private String currentDirectory;
    private int timeout;
    private boolean offline;
    private String settingsXml;
    private String rootFolderOverride;

    public static final void main(String... args) {
        if (args.length == 0) {
            System.err.println("Missing group:artifact:version argument");
            System.exit(1);
        }
        List<String> argsl = new ArrayList<>(Arrays.asList(args));
        boolean single = false;
        if (argsl.get(0).equals("--single")) {
            single = true;
            argsl.remove(0);
        }
        String[] dep = argsl.get(0).split(":");
        if (dep.length != 3) {
            System.err.println("Invalid dependency, should be 'group:artifact:version'");
            System.exit(2);
        }
        DependencyNode res = new mvndeps().getDependencies(dep[0], dep[1], dep[2], single);
        printDependency(res, 0);
    }

    private static void printDependency(DependencyNode dep, int depth) {
        IntStream.range(0, depth).forEach(x -> System.out.print("   "));
        System.out.println(dep);
        for (DependencyNode child : dep.getChildren()) {
            printDependency(child, depth + 1);
        }
    }

    private static RepositorySystem newRepositorySystem() {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService( RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class );
        locator.addService( TransporterFactory.class, FileTransporterFactory.class );
        locator.addService( TransporterFactory.class, HttpTransporterFactory.class );
        
        return locator.getService( RepositorySystem.class );
    }

    private DefaultRepositorySystemSession newSession( RepositorySystem system ) {
        return MavenRepositorySystemUtils.newSession();
    }
    
    private List<RemoteRepository> configureSession(RepositorySystem system, DefaultRepositorySystemSession session){
        DefaultSettingsBuilderFactory factory = new DefaultSettingsBuilderFactory();
        DefaultSettingsBuilder builder = factory.newInstance();

        SettingsBuildingRequest settingsBuilderRequest = new DefaultSettingsBuildingRequest();
        settingsBuilderRequest.setSystemProperties(System.getProperties());
        
        // if we have a root folder, don't read settings at all
        if (rootFolderOverride == null) {
            // find the settings
            String settingsFile = settingsXml;
            if (settingsFile == null) {
                File userSettings = new File(System.getProperty("user.home"), ".m2/settings.xml");
                if (userSettings.exists())
                    settingsFile = userSettings.getAbsolutePath();
            }
            if (settingsFile != null) {
                settingsBuilderRequest.setUserSettingsFile(new File(settingsFile));
            }
        }
        
        // read it
        SettingsBuildingResult settingsBuildingResult;
        try {
            settingsBuildingResult = builder.build(settingsBuilderRequest);
        } catch (SettingsBuildingException e) {
            throw new RuntimeException(e);
        }
        Settings set = settingsBuildingResult.getEffectiveSettings();
        
        // configure the local repo
        String localRepository = rootFolderOverride;
        if (localRepository == null)
            localRepository = set.getLocalRepository();
        if (localRepository == null)
                localRepository = System.getProperty("user.home")+File.separator+".m2"+File.separator+"repository";
        else if (! new File(localRepository).isAbsolute() && currentDirectory != null)
            localRepository = new File(new File(currentDirectory), localRepository).getAbsolutePath();
        LocalRepository localRepo = new LocalRepository( localRepository );

        // set up authentication
        DefaultAuthenticationSelector authenticationSelector = new DefaultAuthenticationSelector();
        for (Server server : set.getServers()){
                AuthenticationBuilder auth = new AuthenticationBuilder();
                if (server.getUsername() != null)
                    auth.addUsername(server.getUsername());
                if (server.getPassword() != null)
                    auth.addPassword(server.getPassword());
                if (server.getPrivateKey() != null)
                    auth.addPrivateKey(server.getPrivateKey(), server.getPassphrase());
                authenticationSelector.add(server.getId(), auth.build());
        }
        session.setAuthenticationSelector(authenticationSelector );
        
        // set up mirrors
        DefaultMirrorSelector mirrorSelector = new DefaultMirrorSelector();
        for (Mirror mirror : set.getMirrors()){
            mirrorSelector.add(mirror.getId(), mirror.getUrl(), mirror.getLayout(), false, mirror.getMirrorOf(), mirror.getMirrorOfLayouts());
        }
        session.setMirrorSelector(mirrorSelector);

        // set up proxies
        DefaultProxySelector proxySelector = new DefaultProxySelector();
        for (Proxy proxy : set.getProxies()){
                if (proxy.isActive()){
                    AuthenticationBuilder auth = new AuthenticationBuilder();
                    if(proxy.getUsername() != null)
                        auth.addUsername(proxy.getUsername());
                    if(proxy.getPassword() != null)
                        auth.addPassword(proxy.getPassword());
                    proxySelector.add(
                            new org.eclipse.aether.repository.Proxy(
                                    proxy.getProtocol(), proxy.getHost(), proxy.getPort(), 
                                    auth.build() ), 
                            proxy.getNonProxyHosts());
                }
        }
        session.setProxySelector(proxySelector);
        
        // set up remote repos
        List<RemoteRepository> repos = new ArrayList<>();
        
        RemoteRepository central = new RemoteRepository.Builder( "central", "default", "https://repo1.maven.org/maven2/" ).build();
        repos.add(central);
        
        Set<String> activeProfiles = new HashSet<>();
        activeProfiles.addAll(set.getActiveProfiles());
        for (Profile profile : set.getProfiles()) {
            Activation activation = profile.getActivation();
            if (activation != null){
                if (activation.isActiveByDefault())
                    activeProfiles.add(profile.getId());
            }
        }
        for (String profileId : activeProfiles) {
            Profile profile = set.getProfilesAsMap().get(profileId);
            if (profile != null){
                addReposFromProfile(repos, profile);
            }
        }
        
        // connection settings
        session.setConfigProperty(ConfigurationProperties.CONNECT_TIMEOUT, timeout);
        session.setOffline(offline || set.isOffline());
        
        session.setLocalRepositoryManager( system.newLocalRepositoryManager( session, localRepo ) );

        return repos;
    }
    
    private void addReposFromProfile(List<RemoteRepository> repos, Profile profile) {
        for(Repository repo : profile.getRepositories()){
            RemoteRepository.Builder remoteRepo = new RemoteRepository.Builder( repo.getId(), repo.getLayout(), repo.getUrl() );

            // policies
            org.apache.maven.settings.RepositoryPolicy repoReleasePolicy = repo.getReleases();
            if (repoReleasePolicy != null){
                String updatePolicy = repoReleasePolicy.getUpdatePolicy();
                // This is the default anyway and saves us a message on STDERR
                if (updatePolicy == null || updatePolicy.isEmpty())
                    updatePolicy = RepositoryPolicy.UPDATE_POLICY_NEVER;
                RepositoryPolicy releasePolicy = new RepositoryPolicy(repoReleasePolicy.isEnabled(), updatePolicy, 
                        repoReleasePolicy.getChecksumPolicy());
                remoteRepo.setReleasePolicy(releasePolicy );
            }
            
            org.apache.maven.settings.RepositoryPolicy repoSnapshotPolicy = repo.getSnapshots();
            if (repoSnapshotPolicy != null){
                String updatePolicy = repoSnapshotPolicy.getUpdatePolicy();
                // This is the default anyway and saves us a message on STDERR
                if (updatePolicy == null || updatePolicy.isEmpty())
                    updatePolicy = RepositoryPolicy.UPDATE_POLICY_NEVER;
                RepositoryPolicy snapshotPolicy = new RepositoryPolicy(repoSnapshotPolicy.isEnabled(), updatePolicy, 
                        repoSnapshotPolicy.getChecksumPolicy());
                remoteRepo.setSnapshotPolicy(snapshotPolicy);
            }
            
            // auth, proxy and mirrors are done in the session
            repos.add(remoteRepo.build());
        }
    }

    private static final DependencySelector NoChildSelector = new DependencySelector(){

        @Override
        public DependencySelector deriveChildSelector(DependencyCollectionContext arg0) {
            return this;
        }

        @Override
        public boolean selectDependency(Dependency arg0) {
            return false;
        }
    };

    public mvndeps() {
        this(null, null, null, false, 0);
    }

    public mvndeps(String currentDirectory, String settingsXml, String rootFolderOverride, boolean offline, int timeout) {
        this.currentDirectory = currentDirectory;
        this.timeout = timeout;
        this.offline = offline;
        this.settingsXml = settingsXml;
        this.rootFolderOverride = rootFolderOverride;
    }

    public DependencyNode getDependencies(String groupId, String artifactId, String version, boolean fetchSingleArtifact) {
        // null extension means auto-detect based on pom
        return getDependencies(groupId, artifactId, version, null, null, fetchSingleArtifact);
    }
    
    public File getLocalRepositoryBaseDir() {
        RepositorySystem repoSystem = newRepositorySystem();
        DefaultRepositorySystemSession session = newSession( repoSystem );
        configureSession(repoSystem, session);
        return session.getLocalRepository().getBasedir();
    }
    
    public DependencyNode getDependencies(String groupId, String artifactId, String version, 
            String classifier, String extension, boolean fetchSingleArtifact) {
        
        RepositorySystem repoSystem = newRepositorySystem();
        DefaultRepositorySystemSession session = newSession( repoSystem );
        List<RemoteRepository> repos = configureSession(repoSystem, session);
        
        session.setTransferListener(new AbstractTransferListener(){
//            @Override
//            public void transferProgressed(TransferEvent arg0) throws TransferCancelledException {
//                System.err.println("Transfer progressed "+arg0.getResource());
//                System.err.println("Data length: "+arg0.getDataLength());
//                System.err.println("Transferred bytes: "+arg0.getTransferredBytes());
//            }
        });
        session.setRepositoryListener(new AbstractRepositoryListener() {
            // Override whatever you're interested in
        });


        Artifact pomResultArtifact = null;
        // I don't think POMs with a classifier exist, so let's not set it
        DefaultArtifact pomArtifact = new DefaultArtifact( groupId, artifactId, null, "pom", version);
        if (extension == null){
            try {
                pomResultArtifact = resolveSingleArtifact(repoSystem, session, repos, pomArtifact);
            } catch (ArtifactResolutionException e) {
                throw new RuntimeException(e);
            }
            if (pomResultArtifact != null){
                extension = findExtension(pomResultArtifact.getFile());
            }
            if (extension == null
                    // we only support jar/aar. ear/war/bundle will resolve as jar anyway
                    || (!extension.equals("jar") && !extension.equals("aar")))
                extension = "jar";
        }
        DefaultArtifact artifact = new DefaultArtifact(groupId, artifactId, classifier, extension, version);
        DependencyNode ret = null;
        
        if (!fetchSingleArtifact) {
            try {
                ret = resolveArtifactWithDependencies(repoSystem, session, repos, artifact);
            } catch (DependencyResolutionException e) {
                if (!isTimeout(e) && pomResultArtifact == null)
                    throw new RuntimeException(e);
                // try a jar-less module
            }
            if (ret == null){
                try {
                    ret = resolveArtifactWithDependencies(repoSystem, session, repos, pomArtifact);
                } catch (DependencyResolutionException e) {
                    throw new RuntimeException(e);
                }
            }
        } else {

            Artifact resultArtifact;
            try {
                resultArtifact = resolveSingleArtifact(repoSystem, session, repos, artifact);
            } catch (ArtifactResolutionException e) {
                if (!isTimeout(e) && pomResultArtifact == null)
                    throw new RuntimeException(e);
                else // go with a jar-less module
                    resultArtifact = pomResultArtifact;
            }
            ret = new DefaultDependencyNode(resultArtifact);
        }
        
        return ret;
    }

    private boolean isTimeout(Throwable e) {
        while (e.getCause() != null)
            e = e.getCause();
        // we can't use its real type since we don't import it
        return e.getClass().getSimpleName().endsWith(".ConnectTimeoutException");
    }

    private DependencyNode resolveArtifactWithDependencies(RepositorySystem repoSystem, 
            DefaultRepositorySystemSession session, 
            List<RemoteRepository> repos, 
            DefaultArtifact artifact) 
            throws DependencyResolutionException {
        
        final Dependency dependency = new Dependency( artifact, JavaScopes.COMPILE );
        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRepositories(repos);
        collectRequest.setRoot( dependency );
        
        DependencyRequest dependencyRequest = new DependencyRequest();
        dependencyRequest.setCollectRequest(collectRequest);

        /*
        dependencyRequest.setFilter(new DependencyFilter(){
            @Override
            public boolean accept(DependencyNode dep, List<DependencyNode> parents) {
                return parents.size() == 0;
            }
        });

        // only get first-level dependencies, of both scopes
        session.setDependencySelector(new DependencySelector(){
            @Override
            public DependencySelector deriveChildSelector(DependencyCollectionContext ctx) {
                //if (myEquals(ctx.getDependency(), dependency))
                if (ctx.getDependency().equals(dependency))
                    return this;
                return NoChildSelector;
            }

            @Override
            public boolean selectDependency(Dependency dep) {
                // Not System, though we could support it
                return JavaScopes.COMPILE.equals(dep.getScope())
                    || JavaScopes.RUNTIME.equals(dep.getScope())
                    || JavaScopes.PROVIDED.equals(dep.getScope())
                    // TEST is useless ATM and is nothing but trouble
//                  || JavaScopes.TEST.equals(dep.getScope())
                            ;
            }
        });
        */

        return repoSystem.resolveDependencies( session, dependencyRequest ).getRoot();
    }

    private Artifact resolveSingleArtifact(RepositorySystem repoSystem, 
            DefaultRepositorySystemSession session, 
            List<RemoteRepository> repos, 
            DefaultArtifact artifact) throws ArtifactResolutionException {
        
        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setArtifact(artifact);
        artifactRequest.setRepositories(repos);

        return repoSystem.resolveArtifact(session, artifactRequest).getArtifact();
    }

    private String findExtension(File pomFile) {
        if (pomFile != null && pomFile.exists()){
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model;
            try (FileReader fileReader = new FileReader(pomFile)){
                model = reader.read(fileReader);
                return model.getPackaging();
            } catch (XmlPullParserException | IOException e) {
                return null;
            }
        };
        return null;
    }

    /*
    private boolean myEquals(Dependency a, Dependency b) {
        if (a == null && b == null)
            return true;
        if (a == null || b == null)
            return false;
        return myEquals(a.getArtifact(), b.getArtifact())
            && a.isOptional() == b.isOptional()
            && Objects.equals(a.getScope(), b.getScope());
    }

    private boolean myEquals(Artifact a, Artifact b) {
        if (a == null && b == null)
            return true;
        if (a == null || b == null)
            return false;
        // Don't use Artifact.equals because it compares Properties which we don't want
        return Objects.equals(a.getArtifactId(), b.getArtifactId())
            && Objects.equals(a.getGroupId(), b.getGroupId())
            && Objects.equals(a.getVersion(), b.getVersion())
            && Objects.equals(a.getClassifier(), b.getClassifier())
            && Objects.equals(a.getExtension(), b.getExtension())
            && Objects.equals(a.getFile(), b.getFile());
    }

    public List<String> resolveVersionRange(String groupId, String artifactId, String versionRange) {
        RepositorySystem repoSystem = newRepositorySystem();
        DefaultRepositorySystemSession session = newSession( repoSystem );
        List<RemoteRepository> repos = configureSession(repoSystem, session);

        Artifact artifact = new DefaultArtifact( groupId, artifactId, "jar", versionRange );

        VersionRangeRequest rangeRequest = new VersionRangeRequest();
        rangeRequest.setArtifact( artifact );
        rangeRequest.setRepositories(repos);

        VersionRangeResult rangeResult;
        try {
            rangeResult = repoSystem.resolveVersionRange( session, rangeRequest );
        } catch (VersionRangeResolutionException e) {
            throw new RuntimeException(e);
        }
        List<String> ret = new ArrayList<>(rangeResult.getVersions().size());
        for (Version version : rangeResult.getVersions())
            ret.add(version.toString());
        return ret;
    }

    public DependencyDescriptor getDependencies(File pomXml, String name, String version) throws IOException {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model;
        try (FileReader fileReader = new FileReader(pomXml)){
            model = reader.read(fileReader);
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
        return new ModelDependencyDescriptor(model);
    }

    public DependencyDescriptor getDependencies(InputStream pomXml, String name, String version) throws IOException {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model;
        try {
            model = reader.read(pomXml);
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
        return new ModelDependencyDescriptor(model);
    }
    */
}
