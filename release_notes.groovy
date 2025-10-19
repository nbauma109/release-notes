/**
 * release_notes.groovy
 *
 * Deterministic release notes generator (no LLM).
 * - Parses "## Commits" bullets with "<hash>: <message>"
 * - Excludes [maven-release-plugin] and merge commits
 * - Classifies each commit into exactly one bucket:
 *   Features & highlights | Bug fixes | Improvements |
 *   Dependencies (Runtime | Test | Maven plugins | Github actions & workflow) |
 *   Chores & Maintenance | Build & Packaging (yaml files | scripts | core) | Documentation
 * - Merges dependency bumps for the same artifact and renders:
 *   "- <first-hash>: Bump <artifact> from <min> to <max>  (also: <other-hashes>) <PR refs>"
 *   If no versions are found, it tries to resolve versions by fetching the PR title(s)
 *   from the linked pull request URL(s). If still unresolved, it prints the first original line.
 *
 * Icons are added to section headers for readability.
 *
 * New:
 * - Optional second argument: path to pom.xml. If provided, we scan test-scoped dependencies and
 *   use "groupId:artifactId" and any ${property} version names as Test dependency keywords.
 *   Example matches in commits: "Bump org.apache.kafka:kafka-clients from ..." OR
 *   "Bump org.eclipse.jdt.version from ..."
 */

import java.util.regex.Pattern
import java.net.http.*
import java.net.*
import java.time.*
import groovy.xml.*

// ------------------------------ I/O ------------------------------

String input = args ? new File(args[0]).getText('UTF-8') : System.in.newReader('UTF-8').text
String pomPath = (args?.length ?: 0) >= 2 ? args[1] : null  // optional

List<String> allLines = input.readLines()

// Slice lines under "## Commits" (if present), else use all lines
int startIdx = allLines.findIndexOf { it.trim().toLowerCase().startsWith('## commits') }
List<String> commitLines = (startIdx >= 0 ? allLines.subList(startIdx + 1, allLines.size()) : allLines)
  .findAll { it.trim().startsWith('-') || it.trim().startsWith('*') }

// Only accept bullets like "- <hash>: <message>"
Pattern bulletPat = ~/(?i)^\s*[-*]\s*([0-9a-f]{7,40}):\s*(.+)$/

// Exclusion patterns
Pattern mvnReleasePat = ~/(?i)\[maven-release-plugin\]/
Pattern tychoUpdateVersionPat = ~/(?i)\[update version\]/
Pattern mergePat      = ~/(?i):\s*Merge(\s|$)/

// ------------------------------ Commit structure ------------------------------

class Commit {
  String id
  String message         // without leading bullet and hash:
  String fullLine        // original full line (for refs)
  String lower           // lowercase message
  List<String> refs = [] // textual refs to show ( "(#123)" and/or "[#123](url)")
  List<String> prUrls = [] // extracted PR URLs like https://github.com/owner/repo/pull/123
}

// ------------------------------ Parsing ------------------------------

List<String> extractRefs(String fullLine, List<String> prUrlsOut) {
  List<String> refs = []
  def shortHash = (fullLine =~ /(?i)[0-9a-f]{7,40}/)
  if (shortHash.find()) refs << "(${shortHash.group()})"

  def pr1 = (fullLine =~ /\(#[0-9]+\)/)
  while (pr1.find()) refs << pr1.group()

  // capture PR number and URL
  def pr2 = (fullLine =~ /\[#([0-9]+)\]\(([^)]+)\)/)
  while (pr2.find()) {
    refs << pr2.group()
    String url = pr2.group(2)
    if (url?.startsWith("http")) prUrlsOut << url
  }

  // de-duplicate, keep order
  List<String> out = []
  refs.each { if (!out.contains(it)) out << it }
  return out
}

List<Commit> commits = []
commitLines.each { line ->
  def m = bulletPat.matcher(line)
  if (!m.find()) return
  String id  = m.group(1)
  String msg = m.group(2)
  String lowerAll = (id + ': ' + msg).toLowerCase()
  if (mvnReleasePat.matcher(lowerAll).find()) return
  if (tychoUpdateVersionPat.matcher(lowerAll).find()) return
  if (mergePat.matcher(lowerAll).find()) return

  Commit c = new Commit(id: id, message: msg, fullLine: line, lower: msg.toLowerCase())
  List<String> prUrlsTmp = []
  c.refs = extractRefs(line, prUrlsTmp)
  c.prUrls.addAll(prUrlsTmp)
  commits << c
}

// ------------------------------ Optional: load test dependency tokens from POM ------------------------------

/**
 * Returns a set of lowercase tokens that indicate Test dependencies:
 * - "groupId:artifactId"
 * - version property name (without ${...}) if dependency version uses a property
 */
Set<String> loadTestTokens(String pomPath) {
  Set<String> out = new LinkedHashSet<>()
  if (!pomPath) return out
  File f = new File(pomPath)
  if (!f.exists()) return out

  def xml = new XmlSlurper(false, false).parse(f)
  // Handle default POM structure; ignore namespaces for simplicity.
  xml.dependencies?.dependency?.each { dep ->
    String scope = (String) (dep.scope?.text() ?: "")
    if (scope?.trim()?.equalsIgnoreCase("test")) {
      String g = (String) (dep.groupId?.text() ?: "")
      String a = (String) (dep.artifactId?.text() ?: "")
      String v = (String) (dep.version?.text() ?: "")
      if (g && a) out << (g + ":" + a).toLowerCase()
      // if version is a property like ${prop.name}, capture "prop.name"
      def m = (v =~ /^\s*\$\{\s*([^}]+)\s*}\s*$/)
      if (m.find()) {
        out << m.group(1).toLowerCase()
      }
    }
  }
  return out
}

final Set<String> testTokens = loadTestTokens(pomPath)

// ------------------------------ Classification ------------------------------

// Keyword patterns (lowercased fragments)
Pattern reFeatures   = ~/(add|introduc|implement|support|enabl|feature|new)/
Pattern reFixes      = ~/(fix|bug|issue|regress|correct|hotfix)/
Pattern reImprove    = ~/(improv|enhanc|optim|tweak|adjust|fine[-\s]?tun)/
Pattern reDocs       = ~/(readme|docs?|documentation|changelog|javadoc)/

// Dependencies (narrow candidate so we do not capture random “update” text)
Pattern reDepNarrow  = ~/\b(bump|upgrade)\b|dependabot\[bot]/
Pattern reTestDep    = ~/(junit|jupiter|testng|mockito|assertj|hamcrest)/
Pattern reMvnPlugin  = ~/(-maven-plugin|maven-\w+-plugin)/
Pattern reGHA        = ~/(actions\/|workflow|-action|create-pull-request)/

// Build and misc
Pattern reYaml       = ~/\.(ya?ml)(\b|$)/
Pattern reScripts    = ~/(\.sh$|\.bat$|\.cmd$|(^|[^a-z])makefile(\b|$))/
Pattern reBuildCore  = ~/(^|[^a-z])(buil|packag|pom\.xml|maven|assembl|launch4j|distr|archiv|tar\.xz|releas|jit)([^a-z]|$)/

// Decide the unique bucket for a commit (ordered precedence)
enum Bucket {
  FEATURES, FIXES, IMPROVEMENTS,
  DEP_RT, DEP_TEST, DEP_MVN, DEP_GHA,
  CHORES, BUILD_YAML, BUILD_SCRIPTS, BUILD_CORE, DOCS
}

// Helper: does message contain a bump of one of the POM test tokens?
boolean containsPomTestBump(String msgLower, Set<String> tokens) {
  if (!tokens || tokens.isEmpty()) return false
  for (String t : tokens) {
    // We only mark as test dep if we see a bump/upgrade/update of that exact token followed by " from"
    // This matches both "groupId:artifactId" and a property name like "org.eclipse.jdt.version".
    if (msgLower.contains("bump " + t + " from")) return true
    if (msgLower.contains("upgrade " + t + " from")) return true
    if (msgLower.contains("update " + t + " from")) return true
  }
  return false
}

// Make classify a closure so it can capture the regex variables above
def classify = { Commit c ->
  def msg = c.lower

  // Build precedence first for YAML/Scripts to avoid overlap
  if (reYaml.matcher(msg).find())     return Bucket.BUILD_YAML
  if (reScripts.matcher(msg).find())  return Bucket.BUILD_SCRIPTS

  // Dependencies (narrow candidate)
  if (reDepNarrow.matcher(msg).find()) {
    // First: if POM is provided, prefer its test dependency tokens
    if (containsPomTestBump(msg, testTokens)) return Bucket.DEP_TEST
    // Otherwise: generic test libs
    if (reTestDep.matcher(msg).find())   return Bucket.DEP_TEST
    if (reMvnPlugin.matcher(msg).find()) return Bucket.DEP_MVN
    if (reGHA.matcher(msg).find())       return Bucket.DEP_GHA
    return Bucket.DEP_RT
  }

  if (reDocs.matcher(msg).find())       return Bucket.DOCS
  if (reFeatures.matcher(msg).find())   return Bucket.FEATURES
  if (reFixes.matcher(msg).find())      return Bucket.FIXES
  if (reImprove.matcher(msg).find())    return Bucket.IMPROVEMENTS
  if (reBuildCore.matcher(msg).find())  return Bucket.BUILD_CORE

  return Bucket.CHORES
}

// ------------------------------ Dependency merging ------------------------------

// Bump parsers (commit messages are already lowercased)
Pattern bumpWithVersions = ~/(bump|upgrade|update)\s+([A-Za-z0-9_.:-]+(?:\/[A-Za-z0-9_.:-]+)?)\s+from\s+([0-9A-Za-z_.-]+)\s+to\s+([0-9A-Za-z_.-]+)/
Pattern bumpNoVersions   = ~/(bump|upgrade)\s+([A-Za-z0-9_.:-]+(?:\/[A-Za-z0-9_.:-]+)?)/

// Version comparison: split on non-alnum, compare numeric segments numerically
int vcmp(String a, String b) {
  List<String> A = a?.split(/[^0-9A-Za-z]+/)?.findAll { it } ?: []
  List<String> B = b?.split(/[^0-9A-Za-z]+/)?.findAll { it } ?: []
  int n = Math.max(A.size(), B.size())
  for (int i=0;i<n;i++) {
    String x = (i < A.size()) ? A[i] : ''
    String y = (i < B.size()) ? B[i] : ''
    if (x == y) continue
    boolean xn = x ==~ /\d+/
    boolean yn = y ==~ /\d+/
    if (xn && yn) return Integer.compare(Integer.valueOf(x), Integer.valueOf(y))
    if (xn && !yn) return -1
    if (!xn && yn) return 1
    int c = x.compareToIgnoreCase(y)
    if (c != 0) return c
  }
  return 0
}
String minv(String a, String b) { if (!a) return b; if (!b) return a; vcmp(a,b) <= 0 ? a : b }
String maxv(String a, String b) { if (!a) return b; if (!b) return a; vcmp(a,b) >= 0 ? a : b }

// Try to parse artifact + versions from a PR title (Dependabot style: "Bump X from A to B")
class ParsedInfo { String artifact; String fromVer; String toVer }
ParsedInfo parseArtifactAndVersionsFromTitle(String title) {
  if (title == null) return null
  def m = (title =~ /(?i)^\s*bump\s+(.+?)\s+from\s+([0-9A-Za-z_.-]+)\s+to\s+([0-9A-Za-z_.-]+)\s*$/)
  if (m.find()) {
    return new ParsedInfo(artifact: m.group(1), fromVer: m.group(2), toVer: m.group(3))
  }
  def m2 = (title =~ /(?i)\bfrom\s+([0-9A-Za-z_.-]+)\s+to\s+([0-9A-Za-z_.-]+)\b/)
  if (m2.find()) {
    return new ParsedInfo(artifact: null, fromVer: m2.group(1), toVer: m2.group(2))
  }
  return null
}

// Fetch PR title via GitHub API if possible; fallback to HTML <title>/og:title>.
// Accepts PR URL like: https://github.com/owner/repo/pull/123
String fetchPullRequestTitle(String prUrl) {
  try {
    if (!(prUrl?.startsWith("http"))) return null

    // Try API if we can extract owner/repo/number
    def m = (prUrl =~ /^https?:\/\/github\.com\/([^\/]+)\/([^\/]+)\/pull\/([0-9]+)(?:$|[\/?#])/)
    String token = System.getenv("GITHUB_TOKEN")
    if (m.find()) {
      String owner = m.group(1)
      String repo  = m.group(2)
      String num   = m.group(3)
      String api   = "https://api.github.com/repos/${owner}/${repo}/pulls/${num}"

      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
      HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(api))
        .timeout(Duration.ofSeconds(15))
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "release-notes-groovy")

      if (token?.trim()) rb.header("Authorization", "Bearer " + token.trim())
      HttpRequest req = rb.GET().build()
      HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString())
      if (res.statusCode() >= 200 && res.statusCode() < 300) {
        def mTitle = (res.body() =~ /"title"\s*:\s*"([^"]*)"/)
        if (mTitle.find()) return mTitle.group(1)
      }
      // fall through to HTML fetch if API failed/limited
    }

    // Fallback: fetch HTML and try to extract <meta property="og:title" ...> or <title>...</title>
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    HttpRequest req = HttpRequest.newBuilder(URI.create(prUrl))
      .timeout(Duration.ofSeconds(15))
      .header("User-Agent", "release-notes-groovy")
      .GET().build()
    HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString())
    if (res.statusCode() >= 200 && res.statusCode() < 300) {
      String html = res.body()
      def og = (html =~ ~/(?i)<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']\s*\/?>/)
      if (og.find()) return og.group(1)
      def t  = (html =~ ~/(?i)<title>([^<]+)<\/title>/)
      if (t.find()) return t.group(1)
    }
  } catch (Throwable ignore) { }
  return null
}

// Resolve artifact + versions via PR titles; first successful wins
ParsedInfo resolveFromPRs(List<String> prUrls) {
  if (!prUrls) return null
  for (String u : prUrls) {
    String title = fetchPullRequestTitle(u)
    ParsedInfo pi = parseArtifactAndVersionsFromTitle(title)
    if (pi != null) return pi
  }
  return null
}

class DepAgg {
  String firstHash
  String firstLine  // "<hash>: <message>"
  String fromVer = ''
  String toVer   = ''
  Set<String> refs = new LinkedHashSet<>()   // refs to show
  Set<String> prUrls = new LinkedHashSet<>() // urls to query for titles
  List<String> additionalHashes = []
}

// Collect merges for one dependency bucket (resolve BEFORE grouping)
def collectDepMerges = { List<Commit> items ->
  Map<String, DepAgg> out = [:]
  items.each { c ->
    String msg = c.lower

    // 1) Try to parse artifact/versions from the commit message itself
    def m = bumpWithVersions.matcher(msg)
    String art = null, from = '', to = ''
    if (m.find()) {
      art = m.group(2); from = m.group(3); to = m.group(4)
    } else {
      m = bumpNoVersions.matcher(msg)
      if (m.find()) {
        art = m.group(2)
      } else if (msg.contains('dependabot[bot]')) {
        def m2 = (msg =~ /(bump|upgrade)\s+([A-Za-z0-9_.:-]+(?:\/[A-Za-z0-9_.:-]+)?)/)
        if (m2.find()) art = m2.group(2)
      }
    }

    // 2) If anything missing, try to resolve from PR titles NOW (pre-merge)
    if (!(art && from && to)) {
      ParsedInfo pi = resolveFromPRs(new ArrayList<>(c.prUrls))
      if (pi != null) {
        if (!art && pi.artifact) art = pi.artifact
        if (!from && pi.fromVer) from = pi.fromVer
        if (!to   && pi.toVer)   to   = pi.toVer
      }
    }

    // 3) If we still cannot determine an artifact, we cannot merge → keep raw
    if (!art) {
      String k = "__RAW__::" + (c.id + ": " + c.message)
      if (!out.containsKey(k)) out[k] = new DepAgg(firstHash: c.id, firstLine: (c.id + ": " + c.message))
      // record refs/urls even for RAW so rendering can still show them
      c.refs.each   { out[k].refs << it }
      c.prUrls.each { out[k].prUrls << it }
      return
    }

    // 4) We have an artifact → aggregate on artifact key
    if (!out.containsKey(art)) {
      out[art] = new DepAgg(firstHash: c.id, firstLine: (c.id + ": " + c.message), fromVer: from, toVer: to)
    } else {
      out[art].fromVer = minv(out[art].fromVer, from)
      out[art].toVer   = maxv(out[art].toVer,   to)
      if (c.id != out[art].firstHash) out[art].additionalHashes << c.id
    }

    // accumulate refs and PR URLs on the artifact aggregate
    c.refs.each   { out[art].refs << it }
    c.prUrls.each { out[art].prUrls << it }
  }
  return out
}

// ------------------------------ Bucketization ------------------------------

Map<Bucket, List<Commit>> buckets = [:].withDefault { [] }
commits.each { c -> buckets[classify(c)] << c }

// ------------------------------ Rendering ------------------------------

StringBuilder sb = new StringBuilder()

// Utilities
def printList = { String header, List<Commit> list ->
  if (!list) return
  sb << "## ${header}\n"
  list.each { c -> sb << "- ${c.id}: ${c.message}\n" }
  sb << "\n"
}

def printDeps = {
  List<Commit> rt   = buckets[Bucket.DEP_RT]
  List<Commit> test = buckets[Bucket.DEP_TEST]
  List<Commit> mvn  = buckets[Bucket.DEP_MVN]
  List<Commit> gha  = buckets[Bucket.DEP_GHA]

  if (!(rt || test || mvn || gha)) return
  sb << "## 📦 Dependencies\n"

  def renderSub = { String title, List<Commit> items ->
    if (!items) return
    Map<String, DepAgg> agg = collectDepMerges(items)
    if (!agg) return
    sb << "### ${title}\n"
    agg.keySet().sort { it.toLowerCase() }.each { key ->
      DepAgg d = agg[key]
      if (key.startsWith("__RAW__::")) {
        sb << "- ${d.firstLine}\n"
      } else {
        String fromV = d.fromVer
        String toV   = d.toVer

        // As a last resort, try again at render time (should be rare now)
        if (!(fromV && toV)) {
          ParsedInfo pi = resolveFromPRs(new ArrayList<>(d.prUrls))
          if (pi != null) {
            if (!fromV && pi.fromVer) fromV = pi.fromVer
            if (!toV   && pi.toVer)   toV   = pi.toVer
          }
        }

        if (fromV && toV) {
          String extra = ""
          if (d.additionalHashes) extra += "  (also: ${d.additionalHashes.join(' ')})"
          if (d.refs)             extra += "  ${d.refs.join(' ')}"
          sb << "- ${d.firstHash}: Bump ${key} from ${fromV} to ${toV}${extra}\n"
        } else {
          // Still unresolved → keep first line exactly (no rephrasing)
          sb << "- ${d.firstLine}\n"
        }
      }
    }
    sb << "\n"
  }

  renderSub("⚙️ Runtime dependencies", rt)
  renderSub("🧪 Test dependencies",    test)
  renderSub("🔧 Maven plugins",        mvn)
  renderSub("🪄 Github actions & workflow", gha)
}

// Sections in required order (icons only)
printList("✨ Features & highlights", buckets[Bucket.FEATURES])
printList("🐞 Bug fixes",              buckets[Bucket.FIXES])
printList("🧩 Improvements",           buckets[Bucket.IMPROVEMENTS])
printDeps()
printList("🧹 Chores & Maintenance",   buckets[Bucket.CHORES])

// Build & Packaging with subsections
List<Commit> buildCore    = buckets[Bucket.BUILD_CORE]
List<Commit> buildYaml    = buckets[Bucket.BUILD_YAML]
List<Commit> buildScripts = buckets[Bucket.BUILD_SCRIPTS]
if (buildCore || buildYaml || buildScripts) {
  sb << "## 🏗️ Build & Packaging\n"
  buildCore.each   { c -> sb << "- ${c.id}: ${c.message}\n" }
  if (buildCore) sb << "\n"
  if (buildYaml) {
    sb << "### 📜 yaml files\n"
    buildYaml.each { c -> sb << "- ${c.id}: ${c.message}\n" }
    sb << "\n"
  }
  if (buildScripts) {
    sb << "### 🧰 scripts\n"
    buildScripts.each { c -> sb << "- ${c.id}: ${c.message}\n" }
    sb << "\n"
  }
}

// Documentation
printList("📚 Documentation",          buckets[Bucket.DOCS])

// Output
print sb.toString()

