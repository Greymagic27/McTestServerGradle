package io.github.greymagic27.McTestServer;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class TestServerPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getTasks().register("McTestServer", TestServerTask.class, task -> {
            task.setGroup("McTestServer");
            task.setDescription("A utility to run a test MC server when developing plugins");
            task.serverVersion = "";
            task.projectDir.set(task.getProjectDir());
        });
    }
}
