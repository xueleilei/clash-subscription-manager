This is a fixed validation snapshot retrieved on 2026-11-03 from the official
[MetaCubeX/meta-rules-dat](https://github.com/MetaCubeX/meta-rules-dat) release.
Raw SHA-256 values were verified against the release asset metadata and are in
`../resources.lock`. The gzip wrappers are deterministic (mtime 0).

The upstream release uses a moving `latest` tag. Builds read these vendored bytes;
they do not follow that tag. Updating resources requires a deliberate change to
both the snapshot and its lock entries.

This product includes GeoLite2 data created by MaxMind, available from
[MaxMind](https://www.maxmind.com). See
[MaxMind's GeoLite terms](https://www.maxmind.com/en/geolite2/eula) for the
applicable database terms. The upstream repository license is preserved in
`UPSTREAM_LICENSE`; see its repository for individual rule-data sources and attribution.
