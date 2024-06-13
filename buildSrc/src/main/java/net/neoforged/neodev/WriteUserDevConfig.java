package net.neoforged.neodev;

import com.google.gson.GsonBuilder;
import net.neoforged.moddevgradle.internal.UserDevConfig;
import net.neoforged.moddevgradle.internal.UserDevRunType;
import net.neoforged.moddevgradle.internal.utils.FileUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class WriteUserDevConfig extends DefaultTask {
    @Inject
    public WriteUserDevConfig() {}

    @Input
    abstract Property<String> getFmlVersion();

    @Input
    abstract Property<String> getMinecraftVersion();

    @Input
    abstract Property<String> getNeoForgeVersion();

    @Input
    abstract Property<String> getNeoFormVersion();

    @Input
    abstract ListProperty<String> getLibraries();

    @Input
    abstract ListProperty<String> getModules();

    @Input
    abstract ListProperty<String> getIgnoreList();

    @OutputFile
    abstract RegularFileProperty getUserDevConfig();

    @TaskAction
    public void writeUserDevConfig() throws IOException {
        var config = new UserDevConfig(
                "net.neoforged:neoform:" + getNeoFormVersion().get() + "@zip",
                "ats/",
                "joined.lzma",
                "patches/",
                "", // TODO sources
                "", // TODO universal
                getLibraries().get(),
                getModules().get(),
                new LinkedHashMap<>());

        for (var runType : RunType.values()) {
            // TODO: for moddev it is "forgeclientuserdev" et al
            var launchTarget = switch (runType) {
                case CLIENT -> "forgeclientdev";
                case DATA -> "forgedatadev";
                case GAME_TEST_SERVER, SERVER -> "forgeserverdev";
            };

            List<String> args = new ArrayList<>();
            Collections.addAll(args,
                    "--gameDir", ".",
                    "--launchTarget", launchTarget,
                    "--fml.fmlVersion", getFmlVersion().get(),
                    "--fml.mcVersion", getMinecraftVersion().get(),
                    "--fml.neoForgeVersion", getNeoForgeVersion().get(),
                    "--fml.neoFormVersion", getNeoFormVersion().get());

            if (runType == RunType.CLIENT) {
                // TODO: this is copied from NG but shouldn't it be the MC version?
                Collections.addAll(args,
                        "--version", getNeoForgeVersion().get());
            }

            if (runType == RunType.CLIENT || runType == RunType.DATA) {
                Collections.addAll(args,
                        "--assetIndex", "{asset_index}",
                        "--assetsDir", "{assets_root}");
            }

            Map<String, String> systemProperties = new LinkedHashMap<>();
            systemProperties.put("java.net.preferIPv6Addresses", "system");
            systemProperties.put("ignoreList", String.join(",", getIgnoreList().get()));
            systemProperties.put("legacyClassPath.file", "{minecraft_classpath_file}");

            if (runType == RunType.CLIENT || runType == RunType.GAME_TEST_SERVER) {
                systemProperties.put("neoforge.enableGameTest", "true");

                if (runType == RunType.GAME_TEST_SERVER) {
                    systemProperties.put("neoforge.gameTestServer", "true");
                }
            }

            config.runs().put(runType.jsonName, new UserDevRunType(
                    false,
                    "cpw.mods.bootstraplauncher.BootstrapLauncher",
                    args,
                    List.of(
                            "-p", "{modules}",
                            "--add-modules", "ALL-MODULE-PATH",
                            "--add-opens", "java.base/java.util.jar=cpw.mods.securejarhandler",
                            "--add-opens", "java.base/java.lang.invoke=cpw.mods.securejarhandler",
                            "--add-exports", "java.base/sun.security.util=cpw.mods.securejarhandler",
                            "--add-exports", "jdk.naming.dns/com.sun.jndi.dns=java.naming"),
                    runType == RunType.CLIENT,
                    runType == RunType.GAME_TEST_SERVER || runType == RunType.SERVER,
                    runType == RunType.DATA,
                    runType == RunType.CLIENT || runType == RunType.GAME_TEST_SERVER,
                    Map.of(
                            "MOD_CLASSES", "{source_roots}"),
                    systemProperties
            ));
        }

        FileUtils.writeStringSafe(
                getUserDevConfig().getAsFile().get().toPath(),
                new GsonBuilder().setPrettyPrinting().create().toJson(config));
    }

    private enum RunType {
        CLIENT("client"),
        DATA("data"),
        GAME_TEST_SERVER("gameTestServer"),
        SERVER("server");

        private final String jsonName;

        RunType(String jsonName) {
            this.jsonName = jsonName;
        }
    }
}
