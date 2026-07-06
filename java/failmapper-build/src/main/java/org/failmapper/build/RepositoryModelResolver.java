package org.failmapper.build;

import java.util.ArrayList;
import java.util.List;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

/**
 * {@link ModelResolver} backed by Maven Resolver: fetches parent POMs and dependencyManagement/BOM
 * import POMs from the local repository (~/.m2/repository) and the configured remote repositories
 * (Maven Central by default). Repositories declared inside visited POMs are appended via
 * {@link #addRepository} as the model builder walks the inheritance chain.
 *
 * <p>Limitation: version RANGES in {@code <parent>}/import coordinates are not expanded — the raw
 * range string is passed to artifact resolution and will fail with a clear message.
 */
final class RepositoryModelResolver implements ModelResolver {

    private final RepositorySystem repositorySystem;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;

    RepositoryModelResolver(
            RepositorySystem repositorySystem,
            RepositorySystemSession session,
            List<RemoteRepository> initialRepositories) {
        this.repositorySystem = repositorySystem;
        this.session = session;
        this.repositories = new ArrayList<>(initialRepositories);
    }

    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        DefaultArtifact pom = new DefaultArtifact(groupId, artifactId, "", "pom", version);
        try {
            ArtifactResult result = repositorySystem.resolveArtifact(
                    session, new ArtifactRequest(pom, repositories, "failmapper-model"));
            return new FileModelSource(result.getArtifact().getFile());
        } catch (ArtifactResolutionException e) {
            throw new UnresolvableModelException(
                    "Cannot resolve POM " + groupId + ":" + artifactId + ":" + version
                            + " from local repo or " + repositories.size() + " remote repositories",
                    groupId, artifactId, version, e);
        }
    }

    @Override
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
    }

    @Override
    public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
        return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
    }

    @Override
    public void addRepository(Repository repository) throws InvalidRepositoryException {
        addRepository(repository, false);
    }

    @Override
    public void addRepository(Repository repository, boolean replace) {
        for (int i = 0; i < repositories.size(); i++) {
            if (repositories.get(i).getId().equals(repository.getId())) {
                if (replace) {
                    repositories.set(i, MavenBuildOracle.toRemoteRepository(repository));
                }
                return;
            }
        }
        repositories.add(MavenBuildOracle.toRemoteRepository(repository));
    }

    @Override
    public ModelResolver newCopy() {
        return new RepositoryModelResolver(repositorySystem, session, repositories);
    }
}
