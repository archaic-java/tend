# Tend

Read [the maintenance skill](skills/maintain-tend/SKILL.md) before making changes, then follow its
task links. If local skills are not automatically discovered, read that file directly.

- JDK 25; named JPMS modules only. No Maven, Gradle, class path or preview features.
- Compile: `javac @cmd/compile`; tests: `java @cmd/test`; CLI: `java @cmd/run`.
- Source dependencies are pinned sibling checkouts linked under lib/src. Gson is a checksum-pinned modular JAR under ignored lib/bin.
- Test with Minau v02 and inline Java assertions with explanatory messages. Fixtures are local and isolated per case.
- Offline tests use no live Incus. The explicitly selected integration suite may use only a fresh disposable CI/test VM.
- Keep the mock grounded in documented Incus HTTP contracts.
- XML describes Incus resources. Add only capabilities required by the experimental digital-garden.
- Volumes with managed files require security.shifted=true; generated file volumes enable it automatically.
- Never log secrets. Keep ownership, retained volumes and failed-operation recovery explicit.

Iterate with offline checks. Run the full integration suite once after a coherent change is ready;
repeat only for a failure or a subsequent change that invalidates the evidence.
