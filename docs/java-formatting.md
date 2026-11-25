# Java source formatting

Use **google-java-format 1.24.0**, AOSP style (four-space indentation), with a JDK 21 formatter runtime. Project compilation still uses its existing Java toolchain.

The explicit source roots are listed in `.java-format.json`. Generated files, external code and build outputs are excluded. Preserve import order, unused imports, long string expressions and Javadoc formatting; keep functional changes separate from formatting changes.

Download the official `google-java-format-1.24.0-all-deps.jar` from Maven Central:
https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/1.24.0/google-java-format-1.24.0-all-deps.jar

SHA-256: `812f805f58112460edf01bf202a8e61d0fd1f35c0d4fabd54220640776ec57a1`.

Check (exit 1 means formatting differs or the formatter reported an error; inspect its output):

```powershell
./tools/format-java.ps1 -FormatterJar <formatter-jar> -Java <jdk-21-java>
```

Apply intentionally with `-Write`, then review the diff and run the relevant compilation checks. The script does not download dependencies, build or publish a mod. It does not rename variables, reorder statements or repair bugs.

Prefer one statement per line and expanded control-flow blocks. Keep short accessors readable; split complex responsibilities in a separate, behavior-reviewed change. Comment on invariants and reasons rather than restating each line.
