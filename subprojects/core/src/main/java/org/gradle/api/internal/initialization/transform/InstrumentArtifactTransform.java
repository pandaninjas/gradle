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

import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import java.io.File;

import static org.gradle.api.internal.initialization.transform.InstrumentArtifactTransform.InstrumentArtifactTransformParameters;

public abstract class InstrumentArtifactTransform implements TransformAction<InstrumentArtifactTransformParameters> {

    public interface InstrumentArtifactTransformParameters extends TransformParameters {
        @InputFiles
        ConfigurableFileCollection getClassHierarchy();
    }

    @InputArtifact
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract Provider<FileSystemLocation> getInput();

    private File getInputAsFile() {
        return getInput().get().getAsFile();
    }

    @Override
    public void transform(TransformOutputs outputs) {
        outputs.file(getInput());

        System.out.println("Hello from " + InstrumentArtifactTransform.class.getSimpleName());
        System.out.println("Class hierarch " + getParameters().getClassHierarchy().getFiles());
        System.out.println("Transforming artifact: " + getInputAsFile().getName() + ", does it exists: " + getInputAsFile().exists());
    }
}
