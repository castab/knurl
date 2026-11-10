# Releasing

## Versioning policy

Knurl follows [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html). One repository version applies to the application image:

- `castab/knurl`

Git tags have a `v` prefix. Stable releases use `vX.Y.Z`; release candidates use `vX.Y.Z-rc.N`. Exact tags are immutable.

## Stable releases

1. Move the intended notes from `Unreleased` into `## [X.Y.Z] - YYYY-MM-DD` in `CHANGELOG.md` and merge them into `main`.
2. Confirm `main` CI is green and run `sh gradlew build --no-daemon` locally.
3. Update local `main`, create an annotated tag, and push it:

   ```bash
   git checkout main
   git pull --ff-only
   git tag -a vX.Y.Z -m "Release vX.Y.Z"
   git push origin vX.Y.Z
   ```

4. The `Publish Docker Image` workflow validates the tag and that its commit is reachable from `main`, then publishes the image with `X.Y.Z`, `X.Y`, `X`, and `latest` tags.
5. After the image publishes, the workflow creates a GitHub Release from the matching changelog section. Verify that workflow before treating the release as complete.

## Release candidates

1. Prepare and merge the target stable version's `CHANGELOG.md` section as above. Do not create a separate `-rc.N` heading.
2. Confirm `main` CI is green and run `sh gradlew build --no-daemon` locally.
3. Tag the current `main` commit and push it:

   ```bash
   git checkout main
   git pull --ff-only
   git tag -a vX.Y.Z-rc.N -m "Release vX.Y.Z-rc.N"
   git push origin vX.Y.Z-rc.N
   ```

4. The `Publish Release Candidate Image` workflow publishes only the exact `X.Y.Z-rc.N` tag for the image and creates a GitHub prerelease. It never updates `latest`, `X`, or `X.Y`.

## Required setup

- Create the `castab/knurl` Docker Hub repository as a public repository before the first release.
- Configure the `DOCKERHUB_USERNAME` GitHub Actions variable and `DOCKERHUB_TOKEN` GitHub Actions secret. The token must have push access to that repository.
- Protect `main` with the required CI checks.
- Protect `v*` tags so only release maintainers can create or update them.

## Combined-application transition

New releases publish only `castab/knurl`, built from the root Dockerfile. The two previous service repositories receive no new tags; existing published versions remain untouched. Deployment manifests must change to the combined image and database baseline described in [operations](operations.md#breaking-upgrade--clean-reset).

No release tag, image push, or GitHub release is part of ordinary implementation/testing. Continue to require explicit operator authorization for publishing.
