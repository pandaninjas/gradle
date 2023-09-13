/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.initialization.transform;

import org.gradle.api.artifacts.transform.CacheableTransform;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.cache.GlobalCache;
import org.gradle.cache.GlobalCacheLocations;
import org.gradle.cache.internal.DefaultGlobalCacheLocations;
import org.gradle.internal.classpath.ClasspathBuilder;
import org.gradle.internal.classpath.ClasspathWalker;
import org.gradle.internal.classpath.TransformedClassPath;
import org.gradle.internal.classpath.transforms.InstrumentingClassTransform;
import org.gradle.internal.classpath.transforms.JarTransform;
import org.gradle.internal.classpath.transforms.JarTransformFactoryForAgent;
import org.gradle.internal.classpath.types.InstrumentingTypeRegistry;
import org.gradle.internal.file.Stat;
import org.gradle.util.internal.GFileUtils;

import javax.inject.Inject;
import java.io.File;
import java.util.List;

import static org.gradle.api.internal.initialization.transform.InstrumentArtifactTransform.InstrumentArtifactTransformParameters;

@CacheableTransform
public abstract class InstrumentArtifactTransform implements TransformAction<InstrumentArtifactTransformParameters> {

    public interface InstrumentArtifactTransformParameters extends TransformParameters {
        @InputFiles
        @PathSensitive(PathSensitivity.NAME_ONLY)
        ConfigurableFileCollection getClassHierarchy();
    }

    @Inject
    public abstract ObjectFactory getObjects();

    @InputArtifact
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract Provider<FileSystemLocation> getInput();

    private File getInputAsFile() {
        return getInput().get().getAsFile();
    }

    @Override
    public void transform(TransformOutputs outputs) {
        // TransformedClassPath.handleInstrumentingArtifactTransform depends on the order and this naming, we should make it more resilient in the future
        String instrumentedJarName = getInput().get().getAsFile().getName().replaceFirst("\\.jar$", TransformedClassPath.INSTRUMENTED_JAR_EXTENSION);
        InstrumentationServices instrumentationServices = getObjects().newInstance(InstrumentationServices.class);
        File outputFile = outputs.file(instrumentedJarName);
        if (instrumentationServices.getGlobalCacheLocations().isInsideGlobalCache(getInputAsFile().getAbsolutePath())) {
            outputs.file(getInput());
        } else {
            File originalFile = outputs.file(getInputAsFile().getName());
            GFileUtils.copyFile(getInputAsFile(), originalFile);
        }

        JarTransformFactoryForAgent transformFactory = instrumentationServices.getJarTransformFactory();
        JarTransform transform = transformFactory.createTransformer(getInputAsFile(), new InstrumentingClassTransform(), InstrumentingTypeRegistry.EMPTY);
        transform.transform(outputFile);
    }

    static class InstrumentationServices {

        private final GlobalCacheLocations globalCacheLocations;
        private final JarTransformFactoryForAgent jarTransformFactory;

        @Inject
        public InstrumentationServices(Stat stat, TemporaryFileProvider temporaryFileProvider, List<GlobalCache> globalCaches) {
            this.globalCacheLocations = new DefaultGlobalCacheLocations(globalCaches);
            this.jarTransformFactory = new JarTransformFactoryForAgent(new ClasspathBuilder(temporaryFileProvider), new ClasspathWalker(stat));
        }

        public GlobalCacheLocations getGlobalCacheLocations() {
            return globalCacheLocations;
        }

        public JarTransformFactoryForAgent getJarTransformFactory() {
            return jarTransformFactory;
        }
    }
}
