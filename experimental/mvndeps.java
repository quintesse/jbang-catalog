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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Activation;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Repository;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilder;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;

public class mvndeps {

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
        System.out.println(dep + " " + dep.getArtifact().getFile());
        for (DependencyNode child : dep.getChildren()) {
            printDependency(child, depth + 1);
        }
    }

    private static RepositorySystem newRepositorySystem() {
		DefaultServiceLocator locator = MavenRepositorySystemUtils
                .newServiceLocator()
                .addService(RepositoryConnectorFactory.class,
                        BasicRepositoryConnectorFactory.class)
                .addService(TransporterFactory.class,
                        FileTransporterFactory.class)
                .addService(TransporterFactory.class,
                        HttpTransporterFactory.class);
		return locator.getService(RepositorySystem.class);
    }

    private DefaultRepositorySystemSession newSession(RepositorySystem system, Settings set) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = newLocalRepository(set);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager( session, localRepo ));
        return session;
    }

    private Settings newEffectiveSettings() {
        DefaultSettingsBuilderFactory factory = new DefaultSettingsBuilderFactory();
        DefaultSettingsBuilder builder = factory.newInstance();

        SettingsBuildingRequest settingsBuilderRequest = new DefaultSettingsBuildingRequest();
        settingsBuilderRequest.setSystemProperties(System.getProperties());
        
        File userSettings = new File(System.getProperty("user.home"), ".m2/settings.xml");
        if (userSettings.exists()) {
            settingsBuilderRequest.setUserSettingsFile(userSettings.getAbsoluteFile());
        }
        
        // read it
        SettingsBuildingResult settingsBuildingResult;
        try {
            settingsBuildingResult = builder.build(settingsBuilderRequest);
        } catch (SettingsBuildingException e) {
            throw new RuntimeException(e);
        }
        Settings set = settingsBuildingResult.getEffectiveSettings();

        return set;
    }

    private LocalRepository newLocalRepository(Settings set) {
        String localRepository = null;
        if (set != null)
            localRepository = set.getLocalRepository();
        if (localRepository == null)
            localRepository = System.getProperty("user.home")+File.separator+".m2"+File.separator+"repository";
        LocalRepository localRepo = new LocalRepository( localRepository );
        return localRepo;
    }
    
    private List<RemoteRepository> newRepositories(Settings set) {
        List<RemoteRepository> repos = new ArrayList<>();
        RemoteRepository central = new RemoteRepository.Builder( "central", "default", "https://repo1.maven.org/maven2/" ).build();
        repos.add(central);
        
        if (set != null) {
            Set<String> activeProfiles = getActiveProfiles(set);
            for (String profileId : activeProfiles) {
                Profile profile = set.getProfilesAsMap().get(profileId);
                if (profile != null){
                    addReposFromProfile(repos, profile);
                }
            }
        }

        return repos;
    }

    private Set<String> getActiveProfiles(Settings set) {
        Set<String> activeProfiles = new HashSet<>();
        activeProfiles.addAll(set.getActiveProfiles());
        for (Profile profile : set.getProfiles()) {
            Activation activation = profile.getActivation();
            if (activation != null){
                if (activation.isActiveByDefault())
                    activeProfiles.add(profile.getId());
            }
        }
        return activeProfiles;
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

    /*
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

    public DependencyNode getDependencies(String groupId, String artifactId, String version, boolean fetchSingleArtifact) {
        RepositorySystem repoSystem = newRepositorySystem();
        Settings set = newEffectiveSettings();        
        DefaultRepositorySystemSession session = newSession(repoSystem, set);
        List<RemoteRepository> repos = newRepositories(set);

        try {
            Artifact dummyRoot = new DefaultArtifact(null, null, null, null);
            Artifact artifact = new DefaultArtifact(groupId, artifactId, "jar", version);
            Dependency dependency = new Dependency(artifact, JavaScopes.COMPILE);

			CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRootArtifact(dummyRoot);
            collectRequest.addDependency(dependency);
            collectRequest.setRepositories(repos);

			DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
			DependencyResult dependencyResult = repoSystem.resolveDependencies(session, dependencyRequest);
			return dependencyResult.getRoot();
        } catch (DependencyResolutionException ex) {
            throw new RuntimeException(ex);
        }
    }
}
