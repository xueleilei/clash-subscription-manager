`fixture-server.p12` is a synthetic self-signed certificate for `fixture.test`.
Its password is `fixture-only-password`; this is test material, never a deployment secret.
The HTTP fetcher test pins that hostname to an isolated local HTTPS server and verifies
that the untrusted certificate is rejected while hostname validation remains enabled.
