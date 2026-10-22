package io.github.hellsong.composemonitor.gradle;

import java.util.Collections;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;

public abstract class ComposeStateMonitorExtension {
    /**
     * Master switch. Non-Android targets (desktop, iOS, web) have no debug/release split at compile
     * time, so gate release builds here, e.g. with a Gradle property.
     */
    public abstract Property<Boolean> getEnabled();

    /** Package or class name prefixes to instrument. Empty means every {@code @ComposableMonitor} function. */
    public abstract ListProperty<String> getIncludeClassPackage();

    /** Android build types that are instrumented. Ignored by AGP's KMP library plugin, which has no variants. */
    public abstract SetProperty<String> getAndroidBuildTypes();

    public abstract Property<Boolean> getIncludeTestCompilations();

    /** Prints the transformed IR of every monitored function as a compiler warning. */
    public abstract Property<Boolean> getVerbose();

    public ComposeStateMonitorExtension() {
        getEnabled().convention(true);
        getIncludeClassPackage().convention(Collections.emptyList());
        getAndroidBuildTypes().convention(Collections.singleton("debug"));
        getIncludeTestCompilations().convention(false);
        getVerbose().convention(false);
    }
}
