/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.repository.impl.meta;

import com.android.annotations.NonNull;
import com.android.repository.api.Dependency;
import com.android.repository.api.LocalPackage;
import com.android.repository.api.RepoManager;
import com.android.repository.api.RepoPackage;

import java.io.File;

/**
 * Implementation of {@link LocalPackage} that can be saved and loaded using JAXB.
 */
public abstract class LocalPackageImpl extends RepoPackageImpl implements LocalPackage {
    private File mRepoRoot;

    @Override
    @NonNull
    public File getLocation() {
        assert mRepoRoot != null : "getLocation called without root set!";
        return new File(mRepoRoot, getPath());
    }

    @Override
    public void setRepositoryRoot(@NonNull File root) {
        mRepoRoot = root;
    }

    /**
     * Creates a {@link LocalPackageImpl} from an arbitrary {@link RepoPackage}. Useful if you
     * have a {@link RepoPackage} of unknown concrete type and want to marshal it using JAXB.
     */
    @NonNull
    public static LocalPackageImpl create(@NonNull RepoPackage p, @NonNull RepoManager repoManager) {
        if (p instanceof LocalPackageImpl) {
            return (LocalPackageImpl)p;
        }
        CommonFactory f = (CommonFactory)repoManager.getCommonModule().createLatestFactory();
        LocalPackageImpl result = f.createLocalPackage();
        result.setVersion(p.getVersion());
        result.setRepositoryRoot(repoManager.getLocalPath());
        result.setLicense(p.getLicense());
        result.setPath(p.getPath());
        for (Dependency d : p.getAllDependencies()) {
            result.addDependency(d);
        }
        result.setObsolete(p.obsolete());
        result.setTypeDetails(p.getTypeDetails());
        result.setDisplayName(p.getDisplayName());
        return result;
    }
}