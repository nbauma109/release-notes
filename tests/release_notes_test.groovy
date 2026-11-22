import java.nio.charset.StandardCharsets

File generator = new File('release_notes.groovy').canonicalFile
assert generator.isFile() : 'Run regression tests from the repository root.'

String rewriteReleaseNotes(File script, String input) {
  File inputFile = File.createTempFile('release-notes-regression-', '.md')
  try {
    inputFile.setText(input, StandardCharsets.UTF_8.name())
    Process process = new ProcessBuilder('groovy', script.absolutePath, inputFile.absolutePath)
      .redirectErrorStream(true)
      .start()
    String output = process.inputStream.getText(StandardCharsets.UTF_8.name())
    assert process.waitFor() == 0 : "Generator failed:\n${output}"
    return output
  } finally {
    inputFile.delete()
  }
}

String categorizedOnly = rewriteReleaseNotes(generator, '''## Features
* api: introduce an endpoint (#12) (developer)

## Chores
* deps: bump package from 1.0 to 2.0 (#13) (dependabot[bot])
''')
assert categorizedOnly.contains('## Features\n* api: introduce an endpoint (#12) (developer)')
assert categorizedOnly.contains('## Chores\n* deps: bump package from 1.0 to 2.0 (#13) (dependabot[bot])')
assert !categorizedOnly.contains('## ✨ Features & highlights')
assert !categorizedOnly.contains('## 📦 Dependencies')

String mixed = rewriteReleaseNotes(generator, '''## Chores
* maintenance: Add new workflow (#20) (developer)

## Commits
- abcdef1: Fix startup failure (developer)

## Documentation
* guide: update installation instructions (#21) (developer)
''')
assert mixed.contains('## Chores\n* maintenance: Add new workflow (#20) (developer)')
assert mixed.contains('## Documentation\n* guide: update installation instructions (#21) (developer)')
assert mixed.contains('## 🐞 Bug fixes\n- abcdef1: Fix startup failure (developer)')
assert !mixed.contains('## ✨ Features & highlights')
assert !mixed.contains('## 📚 Documentation')

String categoryAfterCommits = rewriteReleaseNotes(generator, '''## Commits
- abc1234: Fix loading issue (developer)

## Chores
- fedcba1: Add new deployment configuration (developer)

## Commits
- 123abcd: Update README.md (developer)
''')
assert categoryAfterCommits.contains('## Chores\n- fedcba1: Add new deployment configuration (developer)')
assert categoryAfterCommits.contains('## 🐞 Bug fixes\n- abc1234: Fix loading issue (developer)')
assert categoryAfterCommits.contains('## 📚 Documentation\n- 123abcd: Update README.md (developer)')
assert !categoryAfterCommits.contains('## ✨ Features & highlights')

String nestedCategory = rewriteReleaseNotes(generator, '''## Continuous Integration
### Workflows
* **release:** adjust publishing sequence (developer)
''')
assert nestedCategory.contains('## Continuous Integration\n### Workflows\n* **release:** adjust publishing sequence (developer)')
assert !nestedCategory.contains('## 🏗️ Build & Packaging')

String plainList = rewriteReleaseNotes(generator, '''- abc1234: Fix cache invalidation (developer)
- def5678: Update README.md (developer)
''')
assert plainList.contains('## 🐞 Bug fixes\n- abc1234: Fix cache invalidation (developer)')
assert plainList.contains('## 📚 Documentation\n- def5678: Update README.md (developer)')

println 'Release notes regression tests passed.'
