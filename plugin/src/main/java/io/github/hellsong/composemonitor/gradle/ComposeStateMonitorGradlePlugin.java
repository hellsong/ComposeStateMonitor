package io.github.hellsong.composemonitor.gradle;

import java.util.ArrayList;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin;
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation;
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin;
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType;
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact;
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption;

/**
 * Written in Java and compiled against the oldest supported Kotlin Gradle plugin API, so that it loads on every
 * supported Gradle and KGP version: there is no Kotlin metadata or stdlib for an old Gradle to trip over.
 */
public class ComposeStateMonitorGradlePlugin implements KotlinCompilerPluginSupportPlugin {
    private static final String EXTENSION_NAME = "composeStateMonitor";
    private static final String MAIN_COMPILATION = "main";
    private static final String COMPOSE_GRADLE_PLUGIN_ID = "org.jetbrains.kotlin.plugin.compose";
    private static final String COMPOSE_COMPILER_PLUGIN_ID = "androidx.compose.compiler.plugins.kotlin";

    /** Set once the Kotlin plugin of the project is known. */
    private KotlinVersionSupport.CompilerLine compilerLine;

    @Override
    public void apply(Project target) {
        target.getExtensions().create(EXTENSION_NAME, ComposeStateMonitorExtension.class);
        // The compiler plugin uses unstable IR APIs, so there is one build of it per Kotlin line.
        target.getPlugins().withType(KotlinBasePlugin.class).configureEach(kotlinPlugin -> {
            if (compilerLine == null) compilerLine = resolveCompilerLine(target, kotlinPlugin.getPluginVersion());
        });
    }

    private static KotlinVersionSupport.CompilerLine resolveCompilerLine(Project project, String kotlinVersion) {
        KotlinVersionSupport.CompilerLine line = KotlinVersionSupport.lineFor(kotlinVersion);
        if (line == null) {
            throw new GradleException(
                BuildConfig.COMPILER_PLUGIN_ID + " " + BuildConfig.VERSION + " requires Kotlin "
                    + KotlinVersionSupport.oldestSupported() + " or newer, but " + project.getPath()
                    + " uses Kotlin " + kotlinVersion + "."
            );
        }
        if (KotlinVersionSupport.isNewerThanTested(kotlinVersion)) {
            project.getLogger().warn(
                "{} {} has not been tested with Kotlin {}; using the build for Kotlin {}. If compilation fails, "
                    + "update the plugin or set composeStateMonitor.enabled = false.",
                BuildConfig.COMPILER_PLUGIN_ID, BuildConfig.VERSION, kotlinVersion, line.minKotlin
            );
        }
        project.getLogger().info("{}: using {} for Kotlin {}", BuildConfig.COMPILER_PLUGIN_ID, line.artifactId, kotlinVersion);
        return line;
    }

    @Override
    public boolean isApplicable(KotlinCompilation<?> kotlinCompilation) {
        Project project = kotlinCompilation.getProject();
        ComposeStateMonitorExtension extension = project.getExtensions().getByType(ComposeStateMonitorExtension.class);
        if (!extension.getEnabled().get()) return false;
        // Metadata compilations never reach an IR backend.
        if (kotlinCompilation.getPlatformType() == KotlinPlatformType.common) return false;

        String name = kotlinCompilation.getName();
        boolean isTest = name.equals("test") || name.endsWith("Test");
        if (isTest && !extension.getIncludeTestCompilations().get()) return false;

        if (kotlinCompilation.getPlatformType() == KotlinPlatformType.androidJvm && !name.equals(MAIN_COMPILATION)) {
            // AGP compilations are named after the variant, e.g. "debug" or "freeDebug".
            boolean matchesBuildType = false;
            for (String buildType : extension.getAndroidBuildTypes().get()) {
                String capitalized = buildType.isEmpty() ? buildType
                    : Character.toUpperCase(buildType.charAt(0)) + buildType.substring(1);
                if (name.equals(buildType) || name.endsWith(capitalized)) matchesBuildType = true;
            }
            if (!matchesBuildType) return false;
        }

        if (!project.getPlugins().hasPlugin(COMPOSE_GRADLE_PLUGIN_ID)) {
            throw new GradleException(
                BuildConfig.COMPILER_PLUGIN_ID + " needs the Compose compiler plugin (" + COMPOSE_GRADLE_PLUGIN_ID
                    + ") in " + project.getPath() + "."
            );
        }
        if (compilerLine == null) {
            compilerLine = resolveCompilerLine(project, project.getPlugins().withType(KotlinBasePlugin.class)
                .iterator().next().getPluginVersion());
        }
        return true;
    }

    @Override
    public Provider<List<SubpluginOption>> applyToCompilation(KotlinCompilation<?> kotlinCompilation) {
        Project project = kotlinCompilation.getProject();
        ComposeStateMonitorExtension extension = project.getExtensions().getByType(ComposeStateMonitorExtension.class);

        // Our IR pass must see @Composable calls before the Compose compiler lowers them. Before Kotlin 2.3 there is no
        // flag for that, and the compiler plugin puts itself first instead.
        if (compilerLine.supportsPluginOrder) {
            kotlinCompilation.getCompileTaskProvider().configure(task ->
                task.getCompilerOptions().getFreeCompilerArgs().add(
                    "-Xcompiler-plugin-order=" + BuildConfig.COMPILER_PLUGIN_ID + ">" + COMPOSE_COMPILER_PLUGIN_ID
                )
            );
        }

        return project.provider(() -> {
            List<SubpluginOption> options = new ArrayList<>();
            options.add(new SubpluginOption("enabled", "true"));
            options.add(new SubpluginOption("verbose", extension.getVerbose().get().toString()));
            for (String includePackage : extension.getIncludeClassPackage().get()) {
                options.add(new SubpluginOption("includePackage", includePackage));
            }
            return options;
        });
    }

    @Override
    public String getCompilerPluginId() {
        return BuildConfig.COMPILER_PLUGIN_ID;
    }

    @Override
    public SubpluginArtifact getPluginArtifact() {
        return new SubpluginArtifact(BuildConfig.GROUP, compilerLine.artifactId, BuildConfig.VERSION);
    }

    /** Abstract for Java callers before KGP 2.4. Null means "same as getPluginArtifact()". */
    public SubpluginArtifact getPluginArtifactForNative() {
        return null;
    }
}
