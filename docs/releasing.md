# Releasing

## Versioning policy

Knurl follows [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html). One repository version applies to both application images:

- `castab/knurl-presentation-service`
- `castab/knurl-ingestion-service`

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

4. The `Publish Docker Images` workflow validates the tag and that its commit is reachable from `main`, then publishes both images with `X.Y.Z`, `X.Y`, `X`, and `latest` tags.
5. After both images publish, the workflow creates a GitHub Release from the matching changelog section. Verify that workflow before treating the release as complete.

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

4. The `Publish Release Candidate Images` workflow publishes only the exact `X.Y.Z-rc.N` tag for both images and creates a GitHub prerelease. It never updates `latest`, `X`, or `X.Y`.

## Required setup

- Create the two Docker Hub repositories as public repositories before the first release.
- Configure the `DOCKERHUB_USERNAME` GitHub Actions variable and `DOCKERHUB_TOKEN` GitHub Actions secret. The token must have push access to both repositories.
- Protect `main` with the required CI checks.
- Protect `v*` tags so only release maintainers can create or update them.
