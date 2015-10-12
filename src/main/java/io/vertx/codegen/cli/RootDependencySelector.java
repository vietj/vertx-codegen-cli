package io.vertx.codegen.cli;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.graph.Dependency;

import java.util.ArrayList;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class RootDependencySelector implements DependencySelector {

  final Artifact artifact;
  ArrayList<Artifact> rootDeps = new ArrayList<>();

  public RootDependencySelector(Artifact artifact) {
    this.artifact = artifact;
  }

  @Override
  public boolean selectDependency(Dependency dependency) {
    rootDeps.add(dependency.getArtifact());
    return true;
  }

  @Override
  public DependencySelector deriveChildSelector(DependencyCollectionContext context) {
    Artifact artifact = context.getDependency().getArtifact();
    if (artifact.getGroupId().equals(artifact.getGroupId()) && artifact.getArtifactId().equals(artifact.getArtifactId())) {
      return new SourceDependencySelector();
    } else {
      return new OtherDependencySelector();
    }
  }

  class OtherDependencySelector implements DependencySelector {
    @Override
    public boolean selectDependency(Dependency dependency) {
      if (dependency.isOptional()) {
        return false;
      } else {
        return dependency.getScope().equals("compile");
      }
    }
    @Override
    public DependencySelector deriveChildSelector(DependencyCollectionContext context) {
      return this;
    }
  }

  class SourceDependencySelector implements DependencySelector {
    @Override
    public boolean selectDependency(Dependency dependency) {
      rootDeps.add(dependency.getArtifact());
      return true;
    }
    @Override
    public DependencySelector deriveChildSelector(DependencyCollectionContext context) {
      return new OtherDependencySelector();
    }
  }
}
