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

import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.gradle.api.artifacts.transform.CacheableTransform;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.file.archive.ZipEntry;
import org.gradle.api.internal.file.archive.ZipInput;
import org.gradle.api.internal.file.archive.impl.FileZipInput;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.internal.Pair;
import org.gradle.internal.classpath.ClassData;
import org.gradle.internal.classpath.ClasspathBuilder;
import org.gradle.internal.classpath.ClasspathWalker;
import org.gradle.internal.classpath.InstrumentingTransformer;
import org.gradle.internal.classpath.TransformedClassPath;
import org.gradle.internal.classpath.types.InstrumentingTypeRegistry;
import org.gradle.internal.file.FileException;
import org.gradle.internal.file.FileHierarchySet;
import org.gradle.util.internal.GFileUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.gradle.api.internal.initialization.transform.InstrumentArtifactTransform.InstrumentArtifactTransformParameters;

@CacheableTransform
public abstract class InstrumentArtifactTransform implements TransformAction<InstrumentArtifactTransformParameters> {

    private static final Logger LOGGER = Logging.getLogger(InstrumentArtifactTransform.class);

    private static final int BUFFER_SIZE = 8192;

    public interface InstrumentArtifactTransformParameters extends TransformParameters {
        @InputFiles
        @PathSensitive(PathSensitivity.NAME_ONLY)
        ConfigurableFileCollection getClassHierarchy();

        @Internal
        ConfigurableFileCollection getGlobalCacheLocations();
    }

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
        File outputFile = outputs.file(instrumentedJarName);
        FileHierarchySet globalCacheSet = FileHierarchySet.empty();
        for (File file : getParameters().getGlobalCacheLocations()) {
            globalCacheSet = globalCacheSet.plus(file);
        }
        if (globalCacheSet.contains(getInputAsFile().getAbsolutePath())) {
            outputs.file(getInput());
        } else {
            File originalFile = outputs.file(getInputAsFile().getName());
            GFileUtils.copyFile(getInputAsFile(), originalFile);
        }

        InstrumentingTransformer transformer = new InstrumentingTransformer();
        File jarFile = getInputAsFile();
        try (ZipArchiveOutputStream outputStream = new ZipArchiveOutputStream(new BufferedOutputStream(Files.newOutputStream(outputFile.toPath()), BUFFER_SIZE))) {
            outputStream.setLevel(0);
            try (ZipInput entries = FileZipInput.create(jarFile)) {
                for (ZipEntry entry : entries) {
                    if (entry.isDirectory()) {
                        continue;
                    } else if (!entry.getName().endsWith(".class")) {
                        ClasspathWalker.ZipClasspathEntry classEntry = new ClasspathWalker.ZipClasspathEntry(entry);
                        new ClasspathBuilder.ZipEntryBuilder(outputStream).put(classEntry.getPath().getPathString(), entry.getContent(), classEntry.getCompressionMethod());
                        continue;
                    }
                    ClasspathWalker.ZipClasspathEntry classEntry = new ClasspathWalker.ZipClasspathEntry(entry);
                    ClassReader reader = new ClassReader(classEntry.getContent());
                    ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    Pair<RelativePath, ClassVisitor> chain = transformer.apply(classEntry, classWriter, new ClassData(reader, InstrumentingTypeRegistry.EMPTY));
                    reader.accept(chain.right, 0);
                    byte[] bytes = classWriter.toByteArray();
                    new ClasspathBuilder.ZipEntryBuilder(outputStream).put(chain.left.getPathString(), bytes, classEntry.getCompressionMethod());
                }
            }
        } catch (FileException | IOException e) {
            // Badly formed archive, so discard the contents and produce an empty JAR
            LOGGER.warn("Malformed archive '{}'. Discarding contents.", getInputAsFile().getName(), e);
        }
    }
}
