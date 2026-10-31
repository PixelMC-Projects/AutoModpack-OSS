# Reusable operator entry

The public CLI is `python tools/oss_publisher.py --help`. It accepts explicit authorized file specifications and environment credentials; no site settings or credentials are bundled.

A workstation may install a private wrapper outside Git. Future agents discover its home from `AUTOMODPACK_OSS_OPERATOR_HOME`; the wrapper's profile points at this checkout, its private credentials CSV and restricted signing-key directory. Invoke it without putting any credential in command arguments:

```powershell
$operatorHome = $env:AUTOMODPACK_OSS_OPERATOR_HOME
& (Join-Path $operatorHome 'Publish-Modpack.ps1') -SpecPath '<authorized-release-spec.json>' -SourceRoot '<authorized-source>' -DryRun
& (Join-Path $operatorHome 'Publish-Modpack.ps1') -SpecPath '<authorized-release-spec.json>' -SourceRoot '<authorized-source>'
```

`-DryRun` validates profile/CSV shape and every explicitly authorized source byte; it makes no network call and generates no key. Normal execution creates a local publisher key only if both key files are absent, builds and uploads immutable objects, and exports the active envelope plus a local server release pin. A retry reuses an existing signed release only after the spec, hashes and profile match. It never activates a remote pointer, changes a server/client instance, starts a game, or restarts anything. Credentials exist only in this process environment for the child publisher invocation and are restored afterward. Restrict the private directory with OS ACLs and never enable PowerShell verbose tracing while handling secrets.

For automation, treat a successful upload as staged publication. A server adopts a version only after its matching pack and local pin are explicitly deployed and the operator chooses the maintenance action. See [deployment](oss-deployment.md) for credential separation, release format and activation.
