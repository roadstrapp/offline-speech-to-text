# About this fork

This repository is a Roadstr-maintained copy of **Offline Speech to Text** by Noah Mühl (MIT). The
app in `app/` and `src/` is unchanged upstream code.

The addition is `engine/`: the speech engine extracted into a library other apps can embed, with a
small Kotlin API, a reproducible Android build and its own tests. See [engine/README.md](engine/README.md).

## Staying in sync with upstream

`engine/` and this file are the only additions and touch no upstream file, so merging upstream
should be conflict-free. The daily `Sync upstream` workflow fast-forwards `main` to the relay's
pristine `main`, merges that mirror into a temporary branch, runs the engine tests and opens (or
refreshes) a pull request against `engine/offline-stt-engine`. It can also be run manually from the
Actions tab. The pull request deliberately remains reviewable rather than silently changing the
engine version consumed by Roadstr.

The equivalent manual sequence is:

```sh
git remote add upstream <the original repository>
git fetch upstream
git merge upstream/main
```

After a merge, rebuild and compare the libraries (`engine/scripts/build-android.sh`), and bump the
`transcribe-cpp` version in `engine/Cargo.toml` only together with the model checksums of every host.
