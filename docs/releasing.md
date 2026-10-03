# Releasing GraphKt

Every GraphKt release is published to the Steamstreet Maven repository. Some releases are also published to Maven Central.

- **The Steamstreet repository** is the default target. A release answers at `https://repo.steamstreet.com` within a minute or two of the upload.
- **Maven Central** is for the occasional public release. It takes about two hours for a release to reach `repo1`. A Central release also publishes to the Steamstreet repository, so that repository holds every version.

Releases are patches on the current line, such as 3.0.x. Do not release a minor or major version unless the owner asks for one.

## The Steamstreet repository

The repository is the private S3 bucket `steamstreet-repository` in the Steamstreet AWS account (141660060409, `us-west-2`). CloudFront serves its `maven/release` prefix at `https://repo.steamstreet.com`. Reading needs no credentials, and a missing path answers 404, so Gradle moves on to its next repository.

Consumers add the repository and limit it to the GraphKt group. The plugin marker, `com.steamstreet.graphkt:com.steamstreet.graphkt.gradle.plugin`, is in the same group, so `plugins { id("com.steamstreet.graphkt") }` resolves from the same filter:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven("https://repo.steamstreet.com") {
            content { includeGroup("com.steamstreet.graphkt") }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://repo.steamstreet.com") {
            content { includeGroup("com.steamstreet.graphkt") }
        }
        mavenCentral()
    }
}
```

The bucket, the CloudFront distribution, the domain, and the publishing user are defined in awskt, in `infrastructure/package-repository.yaml`. awskt's `infrastructure/README.md` explains how to change them.

### The publishing profile

The release script publishes as the IAM user `steamstreet-maven-publisher`. That user can only read, write, and list the bucket's `maven/` tree. The script reads the user's access key from the local AWS profile `steamstreet-publisher`. Set `GRAPHKT_PUBLISH_PROFILE` to use a different profile.

The profile holds a static key, not an SSO session. A release never waits on a login, and Gradle's S3 support cannot use an SSO profile. awskt's `infrastructure/README.md` explains how to create and rotate the key. To check the profile, run:

```bash
aws sts get-caller-identity --profile steamstreet-publisher
```

The result must name the user `steamstreet-maven-publisher`.

The script gives the profile to Gradle only as `AWS_PROFILE`, and only for the publishing steps. It removes any AWS keys from the environment of those steps, so that the keys cannot override the profile. The checks run with no AWS credentials at all. Never export the publisher's key as `AWS_ACCESS_KEY_ID`. awskt's live tests run whenever that variable is set, and the publisher's key made them fail and stopped awskt's first release.

## Cutting a release

Run the release from the line's branch, in sync with `origin`:

```bash
git switch 3.0.x
git pull
scripts/release.sh
```

Allow more than 30 minutes for a release. Run it from a shell, or from a tool whose time limit is at least an hour. On a fresh clone in October 2026, the JVM tests took about 2 minutes, the coordinate check about 5, and the upload, which generates the Dokka documentation and signs every file, about 12. A clean `check` for Central adds more. A slow network or a cold Gradle cache adds more again.

Nebula derives the version from the latest tag. The default is a patch bump, so 3.0.2 is followed by 3.0.3.

### What the script does

For the Steamstreet target, the default, the script runs these steps:

1. **Preflight.** It requires a clean working tree, a release branch such as `3.0.x` that matches `origin`, and a working publishing profile in the Steamstreet account.
2. **Version.** It asks nebula for the version, and it stops if that version is already tagged.
3. **Check.** It runs the JVM tests. See [Check levels](#check-levels).
4. **Coordinates.** It publishes to a scratch Maven repository. It refuses any coordinate outside `com.steamstreet.graphkt` and stops if the Gradle plugin marker is missing. This step compiles every target that the host can build, so a native compile error stops the release.
5. **Upload.** It uploads every module, the Gradle plugin, and the plugin marker to `s3://steamstreet-repository/maven/release`. `gradle-plugin` is an ordinary subproject, so it publishes with the rest of the release.
6. **Verify.** It waits until every POM from step 4 answers at `https://repo.steamstreet.com`.
7. **Tag.** Only then does it create and push the tag `vX.Y.Z`.

The tag means that the release is complete. If a run stops before step 7, nothing is tagged. Run the script again, and it uploads the same version again. The script does not use nebula's `final` task for this target, because `final` pushes the tag before the uploads finish. That left awskt 3.1.6 tagged but only partly published when its first run was interrupted.

### Check levels

`--check` sets what the script verifies before it uploads anything:

| Level | What runs | Default for |
|-------|-----------|-------------|
| `jvm` | `jvmTest` in the multiplatform modules and `test` in the JVM-only modules, without a clean | `--target steamstreet` |
| `full` | `clean check`: every target that the host can build, compiled and tested | `--target central` |
| `none` | Nothing. `--skip-check` is the same. | |

With any level, step 4 still compiles every target. `jvm` skips only the native and JavaScript tests.

### Dry run

`--dry-run` stops after step 4, before anything is uploaded or tagged. Use it to check that a release is ready.

### Finishing or backfilling a release

`--resume` publishes the version tagged at `HEAD` to the Steamstreet repository again. It does not run a check, and it does not create a tag. Use it in two cases:

- A release's tag was pushed, but the uploads did not finish.
- An older release, such as one that is only on Maven Central, needs to be in the Steamstreet repository.

Check out the tag first:

```bash
git switch --detach v3.0.2
scripts/release.sh --resume
```

An upload replaces the same coordinates, so running `--resume` twice is harmless.

### Releasing to Maven Central

```bash
scripts/release.sh --target central
```

This target needs `mavenCentralUsername`, `mavenCentralPassword`, and the `signing.*` properties in `~/.gradle/gradle.properties`. It also needs the publishing profile, because it publishes to the Steamstreet repository too. It runs these steps:

1. Steps 1 to 4 above, with a clean `check` by default.
2. **Final.** Nebula's `final` task with `-Pgraphkt.publishTarget=central` uploads every module, the plugin, and the marker to Sonatype and to the Steamstreet repository. It closes the staging repository, then tags and pushes.
3. **Steamstreet check.** The script checks that every POM answers at `https://repo.steamstreet.com`. A gap does not stop the Central release. Run `--resume` from the tag afterward to fill it.
4. **Validate.** It waits for the deployment to reach `VALIDATED`.
5. **Publish.** It publishes the deployment. This step cannot be undone, so the script asks first unless you pass `--yes`.
6. **Verify.** It waits for the artifacts to answer on `repo1`.

Do not release with `./gradlew final` alone. It closes the staging repository but does not publish it. The deployment stops at `VALIDATED`, and `final` exits 0 having shipped nothing to Central. To publish a stalled deployment, open https://central.sonatype.com/publishing/deployments.

## Trying the publishing path

Before you change how GraphKt publishes, try the change against a throwaway prefix in the bucket. CloudFront serves only `maven/release`, so nothing else is visible to consumers:

```bash
PREFIX="maven/trial-$(date +%Y%m%d%H%M%S)"
AWS_PROFILE=steamstreet-publisher ./gradlew publishAllPublicationsToSteamstreetRepository \
  -Pgraphkt.steamstreetRepositoryUrl="s3://steamstreet-repository/$PREFIX"
aws s3 ls --recursive "s3://steamstreet-repository/$PREFIX/" --profile steamstreet-publisher
```

Nebula refuses `-Prelease.stage=final` with uncommitted changes. Without it, the trial publishes a development version, which exercises the same path.

The publisher cannot delete objects. Remove the trial prefix with the owner's administrator profile, and remove only that prefix:

```bash
aws s3 rm --recursive "s3://steamstreet-repository/$PREFIX/" --profile steamstreet
```

## How the build is set up

- The `graphkt.steamstreet-repository` convention in `buildSrc` defines the `steamstreet` Maven repository. Both library conventions apply it, so every module and the Gradle plugin get a `publishAllPublicationsToSteamstreetRepository` task. The task authenticates with `AwsImAuthentication`, which reads the default AWS credential chain.
- `-Pgraphkt.steamstreetRepositoryUrl` overrides the repository URL. The default is `s3://steamstreet-repository/maven/release`.
- `-Pgraphkt.publishTarget` selects the targets of nebula's `final` task. `steamstreet` is the default. `central` adds Sonatype and closes the staging repository. The release script sets this property.
- Signing applies only when `signing.keyId` is set. Central requires signed artifacts. The Steamstreet repository accepts artifacts with or without signatures.
