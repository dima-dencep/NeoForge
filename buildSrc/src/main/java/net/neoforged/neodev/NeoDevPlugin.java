package net.neoforged.neodev;

import net.neoforged.moddevgradle.dsl.InternalModelHelper;
import net.neoforged.moddevgradle.dsl.NeoForgeExtension;
import net.neoforged.moddevgradle.dsl.RunModel;
import net.neoforged.moddevgradle.internal.ArtifactManifestEntry;
import net.neoforged.moddevgradle.internal.CreateArtifactManifestTask;
import net.neoforged.moddevgradle.internal.DistributionDisambiguation;
import net.neoforged.moddevgradle.internal.ModDevPlugin;
import net.neoforged.moddevgradle.internal.OperatingSystemDisambiguation;
import net.neoforged.moddevgradle.internal.PrepareRun;
import net.neoforged.moddevgradle.internal.RunGameTask;
import net.neoforged.moddevgradle.internal.RunUtils;
import net.neoforged.moddevgradle.internal.WriteLegacyClasspath;
import net.neoforged.moddevgradle.internal.utils.ExtensionUtils;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.dsl.DependencyFactory;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Usage;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaToolchainService;

import java.net.URI;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class NeoDevPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getRepositories().maven(repo -> {
            repo.setName("NeoForged maven");
            repo.setUrl("https://maven.neoforged.net/releases/");
        });
        // TODO: why addLast?
        project.getRepositories().addLast(project.getRepositories().maven(repo -> {
            repo.setUrl(URI.create("https://libraries.minecraft.net/"));
            repo.metadataSources(sources -> sources.artifact());
            // TODO: Filter known groups that they ship and dont just run everything against it
        }));
        project.getRepositories().maven(repo -> {
            repo.setName("Mojang Meta");
            repo.setUrl("https://maven.neoforged.net/mojang-meta/");
            repo.metadataSources(sources -> sources.gradleMetadata());
            repo.content(content -> {
                content.includeModule("net.neoforged", "minecraft-dependencies");
            });
        });
        addTemporaryRepositories(project.getRepositories());

        project.getDependencies().attributesSchema(attributesSchema -> {
            attributesSchema.attribute(ModDevPlugin.ATTRIBUTE_DISTRIBUTION).getDisambiguationRules().add(DistributionDisambiguation.class);
            attributesSchema.attribute(ModDevPlugin.ATTRIBUTE_OPERATING_SYSTEM).getDisambiguationRules().add(OperatingSystemDisambiguation.class);
        });
    }

    private void addTemporaryRepositories(RepositoryHandler repositories) {
        repositories.maven(repo -> {
            repo.setName("Temporary Repo for minecraft-dependencies");
            repo.setUrl("https://prmaven.neoforged.net/GradleMinecraftDependencies/pr1");
            repo.content(content -> {
                content.includeModule("net.neoforged", "minecraft-dependencies");
            });
        });

        repositories.maven(repo -> {
            repo.setName("Temporary Repo for neoform");
            repo.setUrl("https://prmaven.neoforged.net/NeoForm/pr10");
            repo.content(content -> {
                content.includeModule("net.neoforged", "neoform");
            });
        });
    }

    public void configureBase(Project project) {
        var configurations = project.getConfigurations();
        var dependencyFactory = project.getDependencyFactory();
        var tasks = project.getTasks();
        var neoDevBuildDir = project.getLayout().getBuildDirectory().dir("neodev");

        var rawNeoFormVersion = project.getProviders().gradleProperty("neoform_version");
        var minecraftVersion = project.getProviders().gradleProperty("minecraft_version");
        var neoFormVersion = minecraftVersion.zip(rawNeoFormVersion, (mc, nf) -> mc + "-" + nf);
        var neoFormRuntimeVersion = project.getProviders().provider(() -> "0.1.48");

        var neoFormRuntimeConfig = configurations.create("neoFormRuntime", files -> {
            files.setCanBeConsumed(false);
            files.setCanBeResolved(true);
            files.defaultDependencies(spec -> {
                spec.addLater(neoFormRuntimeVersion.map(version -> dependencyFactory.create("net.neoforged:neoform-runtime:" + version).attributes(attributes -> {
                    attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, project.getObjects().named(Bundling.class, Bundling.SHADOWED));
                })));
            });
        });

        // Configuration for all artifact that should be passed to NFRT for preventing repeated downloads
        var neoFormRuntimeArtifactManifestNeoForm = configurations.create("neoFormRuntimeArtifactManifestNeoForm", spec -> {
            spec.setCanBeConsumed(false);
            spec.setCanBeResolved(true);
            spec.withDependencies(dependencies -> {
                dependencies.addLater(neoFormVersion.map(version -> {
                    return dependencyFactory.create("net.neoforged:neoform:" + version);
                }));
            });
        });

        var createManifest = tasks.register("createArtifactManifest", CreateArtifactManifestTask.class, task -> {
            task.getNeoForgeModDevArtifacts().addAll(neoFormRuntimeArtifactManifestNeoForm.getIncoming().getArtifacts().getResolvedArtifacts().map(results -> {
                return results.stream().map(ArtifactManifestEntry::new).collect(Collectors.toSet());
            }));
            task.getManifestFile().set(neoDevBuildDir.map(dir -> dir.file("neoform_artifact_manifest.properties")));
        });

        var createSources = tasks.register("createSourcesArtifact", CreateMinecraftArtifactsTask.class, task -> {
            task.getNeoFormRuntime().from(neoFormRuntimeConfig);
            task.getArtifactManifestFile().set(createManifest.get().getManifestFile());
            task.getNeoFormArtifact().set(neoFormVersion);

            var minecraftArtifactsDir = neoDevBuildDir.map(dir -> dir.dir("artifacts"));
            task.getSourcesArtifact().set(minecraftArtifactsDir.map(dir -> dir.file("base-sources.jar")));
            task.getResourcesArtifact().set(minecraftArtifactsDir.map(dir -> dir.file("minecraft-local-resources-aka-client-extra.jar")));
        });

        // TODO: I think I'm missing a delete step
        tasks.register("setup", Copy.class, task -> {
            task.from(project.zipTree(createSources.flatMap(CreateMinecraftArtifactsTask::getSourcesArtifact)));
            task.into(project.file("src/main/java/"));
        });
    }

    public void configureNeoForge(Project project) {
        var configurations = project.getConfigurations();
        var dependencyFactory = project.getDependencyFactory();
        var tasks = project.getTasks();
        var neoDevBuildDir = project.getLayout().getBuildDirectory().dir("neodev");

        var rawNeoFormVersion = project.getProviders().gradleProperty("neoform_version");
        var fmlVersion = project.getProviders().gradleProperty("fancy_mod_loader_version");
        var minecraftVersion = project.getProviders().gradleProperty("minecraft_version");
        var neoForgeVersion = project.provider(() -> (String) project.getVersion()); // TODO: is this correct?
        var neoFormVersion = minecraftVersion.zip(rawNeoFormVersion, (mc, nf) -> mc + "-" + nf);
        var neoFormRuntimeVersion = project.getProviders().provider(() -> "0.1.31");

        var extension = project.getExtensions().create(NeoForgeExtension.NAME, NeoForgeExtension.class);

        var neoFormRuntimeConfig = configurations.create("neoFormRuntime", files -> {
            files.setCanBeConsumed(false);
            files.setCanBeResolved(true);
            files.defaultDependencies(spec -> {
                spec.addLater(neoFormRuntimeVersion.map(version -> dependencyFactory.create("net.neoforged:neoform-runtime:" + version).attributes(attributes -> {
                    attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, project.getObjects().named(Bundling.class, Bundling.SHADOWED));
                })));
            });
        });

        // Configuration for all artifact that should be passed to NFRT for preventing repeated downloads
        var neoFormRuntimeArtifactManifestNeoForm = configurations.create("neoFormRuntimeArtifactManifestNeoForm", spec -> {
            spec.setCanBeConsumed(false);
            spec.setCanBeResolved(true);
            spec.withDependencies(dependencies -> {
                dependencies.addLater(neoFormVersion.map(version -> {
                    return dependencyFactory.create("net.neoforged:neoform:" + version);
                }));
            });
        });
        var neoFormDependencies = configurations.create("neoFormDependencies", spec -> {
            spec.setCanBeConsumed(false);
            spec.setCanBeResolved(true);
            spec.withDependencies(dependencies -> {
                dependencies.addLater(neoFormVersion.map(version -> {
                    var dep = dependencyFactory.create("net.neoforged:neoform:" + version).capabilities(caps -> {
                        caps.requireCapability("net.neoforged:neoform-dependencies");
                    });
                    dep.endorseStrictVersions();
                    return dep;
                }));
            });
        });

        var jstConfiguration = configurations.create("javaSourceTransformer", files -> {
            files.setCanBeConsumed(false);
            files.setCanBeResolved(true);
            files.defaultDependencies(spec -> {
                spec.add(dependencyFactory.create("net.neoforged.jst:jst-cli-bundle:1.0.38")); // TODO: don't hardcode here
            });
        });

        var createManifest = tasks.register("createArtifactManifest", CreateArtifactManifestTask.class, task -> {
            task.getNeoForgeModDevArtifacts().addAll(neoFormRuntimeArtifactManifestNeoForm.getIncoming().getArtifacts().getResolvedArtifacts().map(results -> {
                return results.stream().map(ArtifactManifestEntry::new).collect(Collectors.toSet());
            }));
            task.getManifestFile().set(neoDevBuildDir.map(dir -> dir.file("neoform_artifact_manifest.properties")));
        });

        var createArtifacts = tasks.register("createMinecraftArtifacts", CreateMinecraftArtifactsTask.class, task -> {
            task.getNeoFormRuntime().from(neoFormRuntimeConfig);
            task.getArtifactManifestFile().set(createManifest.get().getManifestFile());
            task.getNeoFormArtifact().set(neoFormVersion);

            var minecraftArtifactsDir = neoDevBuildDir.map(dir -> dir.dir("artifacts"));
            task.getSourcesArtifact().set(minecraftArtifactsDir.map(dir -> dir.file("base-sources.jar")));
            task.getResourcesArtifact().set(minecraftArtifactsDir.map(dir -> dir.file("minecraft-local-resources-aka-client-extra.jar")));
        });

        var applyAt = tasks.register("applyAccessTransformer", ApplyAccessTransformer.class, task -> {
            task.classpath(jstConfiguration);
            task.getInputJar().set(createArtifacts.flatMap(CreateMinecraftArtifactsTask::getSourcesArtifact));
            task.getAccessTransformer().set(project.getRootProject().file("src/main/resources/META-INF/accesstransformer.cfg"));
            task.getOutputJar().set(neoDevBuildDir.map(dir -> dir.file("artifacts/access-transformed-sources.jar")));
            task.getLibraries().from(neoFormDependencies);
            task.getLibrariesFile().set(neoDevBuildDir.map(dir -> dir.file("minecraft-libraries-for-jst.txt")));
        });

        var applyPatches = tasks.register("applyPatches", ApplyPatches.class, task -> {
            task.getOriginalJar().set(applyAt.flatMap(ApplyAccessTransformer::getOutputJar));
            task.getPatchesFolder().set(project.getRootProject().file("patches"));
            task.getPatchedJar().set(neoDevBuildDir.map(dir -> dir.file("artifacts/patched-sources.jar")));
            task.getRejectsFolder().set(project.getRootProject().file("rejects"));
        });

        // TODO: I think I'm missing a delete step
        tasks.register("setup", Copy.class, task -> {
            task.from(project.zipTree(applyPatches.flatMap(ApplyPatches::getPatchedJar)));
            task.into(project.file("src/main/java"));
        });

        var downloadAssets = tasks.register("downloadAssets", DownloadAssetsTask.class, task -> {
            task.getNeoFormArtifact().set(neoFormVersion.map(v -> "net.neoforged:neoform:" + v + "@zip"));
            task.getNeoFormRuntime().from(neoFormRuntimeConfig);
            task.getArtifactManifestFile().set(createManifest.get().getManifestFile());
            task.getAssetPropertiesFile().set(neoDevBuildDir.map(dir -> dir.file("minecraft_assets.properties")));
        });

        var localRuntime = configurations.create("localRuntime", config -> {
            config.withDependencies(dependencies -> {
                dependencies.add(dependencyFactory.create(RunUtils.DEV_LAUNCH_GAV));
            });
        });

        var installerLibrariesConfiguration = configurations.create("installer");
        var modulesConfiguration = configurations.create("moduleOnly");
        var userDevCompileOnlyConfiguration = configurations.create("userdevCompileOnly");

        var writeNeoDevConfig = tasks.register("writeNeoDevConfig", WriteUserDevConfig.class, task -> {
            task.getFmlVersion().set(fmlVersion);
            task.getMinecraftVersion().set(minecraftVersion);
            task.getNeoForgeVersion().set(neoForgeVersion);
            task.getNeoFormVersion().set(neoFormVersion);
            task.getLibraries().addAll(configurationToGavList(installerLibrariesConfiguration));
            task.getLibraries().addAll(configurationToGavList(modulesConfiguration));
            task.getLibraries().addAll(configurationToGavList(userDevCompileOnlyConfiguration));
            task.getModules().addAll(configurationToGavList(modulesConfiguration));
            task.getIgnoreList().addAll("client-extra", "neoforge-");
            task.getIgnoreList().addAll(modulesConfiguration.getIncoming().getArtifacts().getResolvedArtifacts().map(results -> {
                return results.stream().map(r -> r.getFile().getName()).toList();
            }));
            task.getUserDevConfig().set(neoDevBuildDir.map(dir -> dir.file("neodev-userdev-config.json")));
        });

        var ideSyncTask = tasks.register("neoForgeIdeSync");

        Map<RunModel, TaskProvider<PrepareRun>> prepareRunTasks = new IdentityHashMap<>();
        extension.getRuns().configureEach(run -> {
            var prepareRunTask = ModDevPlugin.setupRunInGradle(
                    project,
                    neoDevBuildDir,
                    run,
                    modulesConfiguration,
                    writeNeoDevConfig,
                    spec -> {
                        spec.withDependencies(set -> {
                            set.addLater(neoFormVersion.map(v -> dependencyFactory.create("net.neoforged:neoform:" + v).capabilities(caps -> {
                                caps.requireCapability("net.neoforged:neoform-dependencies");
                            })));
                        });
                        spec.extendsFrom(installerLibrariesConfiguration, modulesConfiguration, userDevCompileOnlyConfiguration);
                    },
                    createArtifacts.get().getResourcesArtifact(),
                    downloadAssets.flatMap(DownloadAssetsTask::getAssetPropertiesFile));
            prepareRunTasks.put(run, prepareRunTask);
            ideSyncTask.configure(task -> task.dependsOn(prepareRunTask));
        });

        ModDevPlugin.configureIntelliJModel(project, ideSyncTask, extension, prepareRunTasks);

        // TODO: configure eclipse

        var genSourcePatches = tasks.register("generateSourcePatches", GenerateSourcePatches.class, task -> {
            task.getOriginalJar().set(applyAt.flatMap(ApplyAccessTransformer::getOutputJar));
            task.getModifiedSources().set(project.file("src/main/java"));
            task.getPatchesJar().set(neoDevBuildDir.map(dir -> dir.file("source-patches.zip")));
        });

        var genPatches = tasks.register("genPatches", Copy.class, task -> {
            task.from(project.zipTree(genSourcePatches.flatMap(GenerateSourcePatches::getPatchesJar)));
            task.into(project.getRootProject().file("patches"));
        });
    }

    private Provider<List<String>> configurationToGavList(Configuration configuration) {
        return configuration.getIncoming().getArtifacts().getResolvedArtifacts().map(results -> {
            return results.stream().map(ModDevPlugin::guessMavenGav).toList();
        });
    }

    // TODO: the only point of this is to configure runs that depend on neoforge. Maybe this could be done with less code duplication...
    // TODO: Gradle says "thou shalt not referenceth otherth projects" yet here we are
    // TODO: depend on neoforge configurations that the moddev plugin also uses
    public void configureExtra(Project project) {
        var neoForgeProject = project.getRootProject().getChildProjects().get("neoforge");

        var dependencyFactory = project.getDependencyFactory();
        var tasks = project.getTasks();
        var neoDevBuildDir = project.getLayout().getBuildDirectory().dir("neodev");

        var extension = project.getExtensions().create(NeoForgeExtension.NAME, NeoForgeExtension.class);

        var rawNeoFormVersion = project.getProviders().gradleProperty("neoform_version");
        var minecraftVersion = project.getProviders().gradleProperty("minecraft_version");
        var neoFormVersion = minecraftVersion.zip(rawNeoFormVersion, (mc, nf) -> mc + "-" + nf);

        // TODO: this is temporary
        var modulesConfiguration = project.getConfigurations().create("moduleOnly", spec -> {
            spec.withDependencies(set -> {
                set.add(projectDep(dependencyFactory, neoForgeProject, "moduleOnly"));
            });
        });

        var downloadAssets = neoForgeProject.getTasks().named("downloadAssets", DownloadAssetsTask.class);
        var createArtifacts = neoForgeProject.getTasks().named("createMinecraftArtifacts", CreateMinecraftArtifactsTask.class);
        var writeNeoDevConfig = neoForgeProject.getTasks().named("writeNeoDevConfig", WriteUserDevConfig.class);

        var localRuntime = project.getConfigurations().create("localRuntime", config -> {
            config.withDependencies(dependencies -> {
                dependencies.add(dependencyFactory.create(RunUtils.DEV_LAUNCH_GAV));
            });
        });

        var ideSyncTask = tasks.register("neoForgeIdeSync");

        Map<RunModel, TaskProvider<PrepareRun>> prepareRunTasks = new IdentityHashMap<>();
        extension.getRuns().configureEach(run -> {
            var prepareRunTask = ModDevPlugin.setupRunInGradle(
                    project,
                    neoDevBuildDir,
                    run,
                    modulesConfiguration,
                    writeNeoDevConfig,
                    spec -> {
                        spec.withDependencies(set -> {
                            set.addLater(neoFormVersion.map(v -> dependencyFactory.create("net.neoforged:neoform:" + v).capabilities(caps -> {
                                caps.requireCapability("net.neoforged:neoform-dependencies");
                            })));
                        });
                        spec.withDependencies(set -> {
                            set.add(projectDep(dependencyFactory, neoForgeProject, "installer"));
                            set.add(projectDep(dependencyFactory, neoForgeProject, "moduleOnly"));
                            set.add(projectDep(dependencyFactory, neoForgeProject, "userdevCompileOnly"));
                        });
                    },
                    createArtifacts.get().getResourcesArtifact(),
                    downloadAssets.flatMap(DownloadAssetsTask::getAssetPropertiesFile));
            prepareRunTasks.put(run, prepareRunTask);
            ideSyncTask.configure(task -> task.dependsOn(prepareRunTask));
        });

        ModDevPlugin.configureIntelliJModel(project, ideSyncTask, extension, prepareRunTasks);

        // TODO: configure eclipse
    }

    private static ProjectDependency projectDep(DependencyFactory dependencyFactory, Project project, String configurationName) {
        var dep = dependencyFactory.create(project);
        dep.setTargetConfiguration(configurationName);
        return dep;
    }
}
