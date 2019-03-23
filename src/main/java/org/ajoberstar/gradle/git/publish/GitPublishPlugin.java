package org.ajoberstar.gradle.git.publish;

import java.util.Optional;

import org.ajoberstar.gradle.git.publish.tasks.GitPublishCommit;
import org.ajoberstar.gradle.git.publish.tasks.GitPublishPush;
import org.ajoberstar.gradle.git.publish.tasks.GitPublishReset;
import org.ajoberstar.grgit.Grgit;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;

public class GitPublishPlugin implements Plugin<Project> {
  static final String RESET_TASK = "gitPublishReset";
  static final String COPY_TASK = "gitPublishCopy";
  static final String COMMIT_TASK = "gitPublishCommit";
  static final String PUSH_TASK = "gitPublishPush";

  @Override
  public void apply(Project project) {
    GitPublishExtension extension = project.getExtensions().create("gitPublish", GitPublishExtension.class, project);

    extension.getCommitMessage().set("Generated by gradle-git-publish.");

    // if using the grgit plugin, default to the repo's origin
    project.getPluginManager().withPlugin("org.ajoberstar.grgit", plugin -> {
      // TODO should this be based on tracking branch instead of assuming origin?
      Optional.ofNullable((Grgit) project.findProperty("grgit")).ifPresent(grgit -> {
        extension.getRepoUri().set(getOriginUri(grgit));
        extension.getReferenceRepoUri().set(grgit.getRepository().getRootDir().toURI().toString());
      });
    });

    extension.getRepoDir().set(project.getLayout().getBuildDirectory().dir("gitPublish"));

    GitPublishReset reset = createResetTask(project, extension);
    Task copy = createCopyTask(project, extension);
    Task commit = createCommitTask(project, extension, reset.getGrgit());
    Task push = createPushTask(project, extension, reset.getGrgit());
    push.dependsOn(commit);
    commit.dependsOn(copy);
    copy.dependsOn(reset);

    // always close the repo at the end of the build
    project.getGradle().buildFinished(result -> {
      project.getLogger().info("Closing Git publish repo: {}", extension.getRepoDir().get());
      if (reset.getGrgit().isPresent()) {
        reset.getGrgit().get().close();
      }
    });
  }

  private GitPublishReset createResetTask(Project project, GitPublishExtension extension) {
    return project.getTasks().create(RESET_TASK, GitPublishReset.class, task -> {
      task.setGroup("publishing");
      task.setDescription("Prepares a git repo for new content to be generated.");
      task.getRepoDirectory().set(extension.getRepoDir());
      task.getRepoUri().set(extension.getRepoUri());
      task.getReferenceRepoUri().set(extension.getReferenceRepoUri());
      task.getBranch().set(extension.getBranch());
      task.setPreserve(extension.getPreserve());
    });
  }

  private Copy createCopyTask(Project project, GitPublishExtension extension) {
    return project.getTasks().create(COPY_TASK, Copy.class, task -> {
      task.setGroup("publishing");
      task.setDescription("Copy contents to be published to git.");
      task.with(extension.getContents());
      task.into(extension.getRepoDir());
    });
  }

  private GitPublishCommit createCommitTask(Project project, GitPublishExtension extension, Provider<Grgit> grgitProvider) {
    return project.getTasks().create(COMMIT_TASK, GitPublishCommit.class, task -> {
      task.setGroup("publishing");
      task.setDescription("Commits changes to be published to git.");
      task.getGrgit().set(grgitProvider);
      task.getMessage().set(extension.getCommitMessage());
    });
  }

  private GitPublishPush createPushTask(Project project, GitPublishExtension extension, Provider<Grgit> grgitProvider) {
    return project.getTasks().create(PUSH_TASK, GitPublishPush.class, task -> {
      task.setGroup("publishing");
      task.setDescription("Pushes changes to git.");
      task.getGrgit().set(grgitProvider);
      task.getBranch().set(extension.getBranch());
    });
  }

  private String getOriginUri(Grgit grgit) {
    return grgit.getRemote().list().stream()
        .filter(remote -> remote.getName().equals("origin"))
        .map(remote -> remote.getUrl())
        .findAny()
        .orElse(null);
  }
}
