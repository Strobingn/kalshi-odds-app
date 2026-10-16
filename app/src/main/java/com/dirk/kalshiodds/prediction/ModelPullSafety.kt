package com.dirk.kalshiodds.prediction

/**
 * Model-pull safety (0.3.37). A downloaded model is only accepted when ALL hold:
 *  - release tag is `model-YYYYMMDD` (never `edge-model-main` / `edge-model-latest`),
 *    and the manifest tag matches the release tag when both are known;
 *  - manifest says beat_market = true explicitly;
 *  - not synthetic;
 *  - package id matches this app;
 *  - sha256 of the downloaded model matches the manifest;
 *  - ≥ [ModelActivation.MIN_ACTIVATE_ROWS] training rows.
 * Returns null when safe, else the rejection reason. Pure; unit-tested.
 */
object ModelPullSafety {
    fun reject(
        manifest: EdgeModelManifest,
        modelRaw: String,
        expectedPackage: String,
        releaseTag: String? = null
    ): String? {
        if (releaseTag != null && !ModelActivation.acceptableTag(releaseTag)) {
            return "Release tag '$releaseTag' is not model-YYYYMMDD"
        }
        if (!ModelActivation.acceptableTag(manifest.tag)) {
            return "Manifest tag '${manifest.tag}' is not model-YYYYMMDD"
        }
        if (releaseTag != null && !releaseTag.trim().equals(manifest.tag.trim(), ignoreCase = true)) {
            return "Manifest tag '${manifest.tag}' does not match release '$releaseTag'"
        }
        if (manifest.beatMarketFlag != true) return "Manifest does not say beat_market=true"
        if (manifest.synthetic || manifest.dataSource == EdgeModelManifest.PROVENANCE_SYNTHETIC) {
            return "Manifest is synthetic"
        }
        if (manifest.packageId != expectedPackage) {
            return "Model package is '${manifest.packageId}', expected $expectedPackage"
        }
        if (manifest.sha256.isBlank() || !ModelDigest.matches(manifest.sha256, modelRaw)) {
            return "Model integrity check failed (sha256)"
        }
        if (manifest.nRows < ModelActivation.MIN_ACTIVATE_ROWS) {
            return "Training rows ${manifest.nRows} below ${ModelActivation.MIN_ACTIVATE_ROWS}"
        }
        return null
    }
}
