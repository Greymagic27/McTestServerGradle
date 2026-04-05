package io.github.greymagic27.McTestServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.TaskAction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class TestServerTask extends DefaultTask {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public String serverVersion;
    private final List<PluginSpec> plugins = new ArrayList<>();
    public DirectoryProperty projectDir;
    private boolean shutdownHookAdded = false;
    private Process serverProcess;

    public TestServerTask() {
        this.projectDir = getProject().getObjects().directoryProperty();
    }

    private static boolean isStableVersion(String v) {
        return !v.contains("-");
    }

    private static int compareVersions(String v1, String v2) {
        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < p1.length ? Integer.parseInt(p1[i]) : 0;
            int n2 = i < p2.length ? Integer.parseInt(p2[i]) : 0;
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }

    @TaskAction
    public void runTask() throws IOException, InterruptedException {
        Path tempServerDir = Files.createTempDirectory("mc-server-");
        getLogger().lifecycle("Temp server directory: " + tempServerDir);
        Path pluginDir = tempServerDir.resolve("plugins");
        Files.createDirectories(pluginDir);
        String mcVersion = (serverVersion != null && !serverVersion.isBlank()) ? serverVersion : fetchLatestVersion();
        int build = fetchLatestBuild(mcVersion);
        CompletableFuture<Void> packageFuture = CompletableFuture.runAsync(() -> {
            try {
                buildPlugin();
            } catch (IOException | RuntimeException e) {
                throw new RuntimeException("Failed to package plugin", e);
            }
        });
        CompletableFuture<Path> paperFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return downloadPaper(mcVersion, build, tempServerDir);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Failed to download PaperMC", e);
            }
        });
        packageFuture.join();
        Path pluginJar = findPluginJar();
        Files.copy(pluginJar, pluginDir.resolve(pluginJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        for (PluginSpec plugin : plugins) {
            plugin.validate();
            downloadPlugin(plugin, pluginDir);
        }
        Path paperJar = paperFuture.join();
        Files.writeString(tempServerDir.resolve("eula.txt"), "eula=true\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        ProcessBuilder pb = new ProcessBuilder("java", "-Xmx2G", "-jar", paperJar.toString(), "nogui");
        pb.directory(tempServerDir.toFile());
        pb.redirectErrorStream(true);
        serverProcess = pb.start();
        addShutdownHook();
        handleConsole(serverProcess, tempServerDir);
        int exitCode = serverProcess.waitFor();
        if (exitCode != 0) throw new RuntimeException("Server exited with code: " + exitCode);
        deleteRecursive(tempServerDir);
    }

    private void addShutdownHook() {
        if (!shutdownHookAdded) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (serverProcess != null && serverProcess.isAlive()) stopServer();
            }));
            shutdownHookAdded = true;
        }
    }

    private void buildPlugin() throws IOException {
        try {
            String buildTool = detectBuildTool();
            Path base = projectDir.getAsFile().get().toPath();
            if (!"gradle".equalsIgnoreCase(buildTool)) throw new RuntimeException("Unknown build tool: " + buildTool);

            boolean hasShadow = hasShadowPlugin(base);
            String task = hasShadow ? "shadowJar" : "build";
            getLogger().lifecycle("Packaging plugin using Gradle: " + task);

            boolean hasWrapperJar = Files.exists(base.resolve("gradle/wrapper/gradle-wrapper.jar"));
            List<String> candidates = new ArrayList<>();
            if (hasWrapperJar) {
                candidates.add(".\\gradlew.bat");
                candidates.add("./gradlew.bat");
                candidates.add("./gradlew");
            }
            candidates.add("gradle.bat");
            candidates.add("gradle");

            ProcessBuilder pb = null;
            String chosenCmd = null;
            for (String cmd : candidates) {
                try {
                    ProcessBuilder test = new ProcessBuilder(cmd, "--version");
                    test.directory(base.toFile());
                    test.redirectErrorStream(true);
                    Process testProcess = test.start();
                    testProcess.waitFor();
                    pb = new ProcessBuilder(cmd, task);
                    chosenCmd = cmd;
                    break;
                } catch (IOException | InterruptedException ignored) {
                }
            }

            if (pb == null) throw new RuntimeException("Could not find any Gradle executable to use");
            getLogger().lifecycle("Using Gradle command: " + chosenCmd);

            pb.directory(base.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    getLogger().lifecycle("[child] " + line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) throw new RuntimeException("Gradle build failed with exit code: " + exit);
        } catch (RuntimeException | InterruptedException e) {
            throw new RuntimeException("Failed to package plugin", e);
        }
    }

    private boolean hasShadowPlugin(Path projectDir) {
        try {
            List<Path> files = new ArrayList<>();
            if (Files.exists(projectDir.resolve("build.gradle"))) files.add(projectDir.resolve("build.gradle"));
            if (Files.exists(projectDir.resolve("build.gradle.kts"))) files.add(projectDir.resolve("build.gradle.kts"));
            for (Path file : files) {
                String content = Files.readString(file);
                if (content.contains("com.gradleup.shadow") || content.contains("id \"com.gradleup.shadow\"") || content.contains("id(\"com.gradleup.shadow\")")) return true;
            }
        } catch (IOException e) {
            getLogger().error("Failed to detect Shadow plugin", e);
        }
        return false;
    }

    private Path findPluginJar() throws IOException {
        Path base = projectDir.getAsFile().get().toPath();
        try (Stream<Path> walk = Files.walk(base)) {
            List<Path> libsDirs = walk.filter(Files::isDirectory).filter(p -> p.getFileName().toString().equalsIgnoreCase("libs") && p.getParent().getFileName().toString().equalsIgnoreCase("build")).toList();
            if (libsDirs.isEmpty()) throw new IOException("No 'build/libs' directory found in project tree starting at: " + base);
            Path newestJar = null;
            long newestTime = -1;
            for (Path target : libsDirs) {
                try (Stream<Path> files = Files.walk(target)) {
                    for (Path f : files.filter(Files::isRegularFile).toList()) {
                        String name = f.getFileName().toString();
                        if (!name.endsWith(".jar")) continue;
                        try (JarFile jar = new JarFile(f.toFile())) {
                            if (jar.getEntry("plugin.yml") != null || jar.getEntry("paper-plugin.yml") != null) {
                                long modTime = f.toFile().lastModified();
                                if (modTime > newestTime) {
                                    newestJar = f;
                                    newestTime = modTime;
                                }
                            }
                        }
                    }
                }
            }
            if (newestJar == null) throw new IOException("No valid plugin JAR found in any 'build/libs' directory under: " + base);
            getLogger().lifecycle("Found plugin jar: " + newestJar);
            return newestJar;
        }
    }

    private String fetchLatestVersion() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://fill.papermc.io/v3/projects/paper")).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(res.body());
        JsonNode versions = root.get("versions");
        List<String> stableVersions = new ArrayList<>();
        for (JsonNode vNode : versions) {
            if (vNode.isArray()) {
                for (JsonNode inner : vNode) {
                    String version = inner.asString();
                    if (isStableVersion(version)) stableVersions.add(version);
                }
            } else if (vNode.isString()) {
                String version = vNode.asString();
                if (isStableVersion(version)) stableVersions.add(version);
            }
        }
        stableVersions.sort((v1, v2) -> compareVersions(v2, v1));
        String latest = stableVersions.getFirst();
        getLogger().lifecycle("Latest PaperMC version: " + latest);
        return latest;
    }

    private int fetchLatestBuild(String version) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds")).build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode builds = MAPPER.readTree(res.body());
        int max = -1;
        for (JsonNode b : builds) {
            int id = b.get("id").asInt();
            max = Math.max(max, id);
        }
        return max;
    }

    private Path downloadPaper(String version, int build, Path dir) throws IOException, InterruptedException {
        String metaUrl = "https://fill.papermc.io/v3/projects/paper/versions/" + version + "/builds/" + build;
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder().uri(URI.create(metaUrl)).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode downloads = MAPPER.readTree(res.body()).get("downloads");
        String downloadUrl = downloads.get("server:default").get("url").asString();
        Path jarPath = dir.resolve("paper.jar");
        HTTP.send(HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build(), HttpResponse.BodyHandlers.ofFile(jarPath));
        return jarPath;
    }

    private void handleConsole(Process process, Path tempServerDir) {
        Thread consoleThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream())); BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream())); BufferedReader userInput = new BufferedReader(new InputStreamReader(System.in))) {
                Thread outputThread = getThread(reader, writer);
                outputThread.start();
                String input;
                while ((input = userInput.readLine()) != null) {
                    input = input.trim();
                    if ("stop".equalsIgnoreCase(input)) {
                        stopServer();
                        break;
                    }
                    if ("m".equalsIgnoreCase(input)) {
                        getLogger().lifecycle("Opening server directory: " + tempServerDir);
                        openFolder(tempServerDir);
                    } else {
                        writer.write(input + "\n");
                        writer.flush();
                    }
                }
            } catch (IOException e) {
                getLogger().error("Console handling error", e);
            }
        });
        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    private Thread getThread(BufferedReader reader, BufferedWriter writer) {
        Thread outputThread = new Thread(() -> {
            String line;
            try {
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    if (line.contains("Done (")) {
                        writer.write("op Greymagic27\n");
                        writer.flush();
                    }
                    if (line.contains("left the game")) {
                        stopServer();
                        break;
                    }
                }
            } catch (IOException e) {
                getLogger().error("Error reading server output", e);
            }
        });
        outputThread.setDaemon(true);
        return outputThread;
    }

    private void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    getLogger().warn("Failed to delete {}", p, e);
                }
            });
        }
    }

    private void downloadPlugin(PluginSpec plugin, Path pluginDir) throws IOException, InterruptedException {
        Path out = pluginDir.resolve(plugin.pluginName.endsWith(".jar") ? plugin.pluginName : plugin.pluginName + ".jar");
        getLogger().lifecycle("Downloading additional plugin: " + plugin.pluginName + " from " + plugin.pluginUrl);
        HTTP.send(HttpRequest.newBuilder().uri(URI.create(plugin.pluginUrl)).build(), HttpResponse.BodyHandlers.ofFile(out));
    }

    private String detectBuildTool() {
        Path base = projectDir.getAsFile().get().toPath();
        if (Files.exists(base.resolve("build.gradle")) || Files.exists(base.resolve("build.gradle.kts"))) {
            return "gradle";
        }
        throw new RuntimeException("No build tool detected in " + base);
    }

    private void stopServer() {
        if (serverProcess != null && serverProcess.isAlive()) {
            try {
                getLogger().warn("Stopping server");
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(serverProcess.getOutputStream()));
                writer.write("stop\n");
                writer.flush();
                serverProcess.destroyForcibly();
                serverProcess.waitFor();
            } catch (IOException | InterruptedException e) {
                getLogger().error("Failed to stop server", e);
            }
        }
    }

    private void openFolder(Path folder) throws IOException {
        new ProcessBuilder("explorer.exe", folder.toAbsolutePath().toString()).start();
    }

    @Input
    public String getServerVersion() {
        return serverVersion;
    }

    @Input
    public List<PluginSpec> getAdditionalPlugins() {
        return plugins;
    }

    @InputDirectory
    public DirectoryProperty getProjectDir() {
        return projectDir;
    }

    public static class PluginSpec implements Serializable {
        @Input
        public String pluginName;
        @Input
        public String pluginUrl;

        public void validate() {
            if (pluginName == null || pluginName.isBlank()) throw new IllegalArgumentException("pluginName is required for PluginSpec");
            if (pluginUrl == null || pluginUrl.isBlank()) throw new IllegalArgumentException("pluginUrl is required for PluginSpec");
        }
    }

    @SuppressWarnings("unused")
    public void additionalPlugins(String name, String url) {
        PluginSpec spec = new PluginSpec();
        spec.pluginName = name;
        spec.pluginUrl = url;
        plugins.add(spec);
    }
}