package net.neoforged.neodev;

import net.neoforged.moddevgradle.internal.NeoFormRuntimeTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.util.List;

abstract class DownloadAssetsTask extends NeoFormRuntimeTask {
    @Inject
    public DownloadAssetsTask() {
    }

    @Input
    abstract Property<String> getNeoFormArtifact();

    @OutputFile
    abstract RegularFileProperty getAssetPropertiesFile();

    @InputFile
    @Optional
    abstract RegularFileProperty getArtifactManifestFile();

    @TaskAction
    public void createArtifacts() {
        var artifactId = getNeoFormArtifact().get();

        run(List.of(
                "--artifact-manifest", getArtifactManifestFile().get().getAsFile().getAbsolutePath(),
                "download-assets",
                "--neoform", artifactId,
                "--output-properties-to", getAssetPropertiesFile().get().getAsFile().getAbsolutePath()
        ));
    }
}
