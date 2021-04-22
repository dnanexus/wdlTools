## Setup

You will need to create a GitHub personal access token (this is required by the sbt-github-packages plugin). In GitHub settings, go to "Developer settings > Personal access token" and create a new token with "write:packages" and "read:packages" scopes only. Then, export the `GITHUB_TOKEN` environment variable with this token as the value. For example, in your `.profile`:

```bash
export GITHUB_TOKEN=<your personal access token>
```

On macOS, you may also want to add this token into your global environment so it is visible to your IDE:

```bash
launchctl setenv GITHUB_TOKEN $GITHUB_TOKEN
```

## Making a change

If you want to make a change to wdlTools, do the following:

1. Checkout the `develop` branch.
2. Create a new branch with your changes. Name it something meaningful, like `APPS-123-download-bug`.
3. If the current snapshot version matches the release version, increment the snapshot version.
    - For example, if the current release is `1.0.0` and the current snapshot version is `1.0.0-SNAPSHOT`, increment the snapshot version to `1.0.1-SNAPSHOT`.
3. Make your changes. Test locally using `sbt test`.
4. Update the release notes under the top-most header (which should be "in develop").
5. If the current snapshot version only differs from the release version by a patch, and you added any new functionality (vs just fixing a bug), increment the minor version instead.
    - For example, when you first created the branch you set the version to `1.0.1-SNAPSHOT`, but then you realized you needed to add a new function to the public API, change the version to `1.1.0-SNAPSHOT`.
6. When you are done, create a pull request against the `develop` branch.

### Building a local version for testing

- Set version name in `<project>/src/main/resources/application.conf` to `X.Y.Z-SNAPSHOT`.
- Run `sbt publishLocal`, which will [publish to your Ivy local file repository](https://www.scala-sbt.org/1.x/docs/Publishing.html).
- In any downstream project that will depend on the changes you are making, set the dependency version in `build.sbt` to `X.Y.Z-SNAPSHOT`.

### Merging the PR

When a PR is merged into `develop`, SNAPSHOT packages are automatically published to GitHub packages. When you push to `develop` (including merging a PR), you should announce that you are doing so (e.g. via GChat) for two reasons:

* Publishing a new snapshot requires deleting the existing one. If someone is trying to fetch the snapshot from the repository at the time when the snapshot workflow is running, they will get a "package not found" error.
* Although unlikely, it is possible that if two people merge into `develop` at around the same time, the older SNAPSHOT will overwrite the newer one.

## Releasing

### Beginning the release

1. Checkout the develop branch (either HEAD or the specific commit you want to release)
2. Create a release branch named with the version number, e.g. `release-2.4.2`
3. Update the version numbers in application.conf files (remove "-SNAPSHOT")
4. Update the release notes
    - Change the top header from "in develop" to "<version> (<date>)"

### Releasing to GitHub

1. Push the release branch to GitHub.
2. Run the release action.
3. Go to the "Releases" page on GitHub and publish the draft release.

### Releasing to Maven

Note: this process is currently coordinated by John Didion - please request from him a release of the updated library(ies).

1. From the release branch, run `sbt publishSigned`. You will need to have the SonaType PGP private key on your machine, and you will need the password.
2. Go to [nexus repository manager](https://oss.sonatype.org/#stagingRepositories), log in, and go to "Staging Repositories".
3. Check the repository to release; there should only be one, but if there are more check the contents to find yours.
4. Click the "Close" button. After a few minutes, hit "Refresh". The "Release" button should become un-grayed. If not, wait a few more minutes and referesh again.
5. Click the "Release" button.

### Completing the release

If you encounter any additional issues while creating the release, you will need to make the fixes in `develop` and then merge them into the release branch.

To complete the release, open a PR to merge the release branch into main. You can then delete the release branch.