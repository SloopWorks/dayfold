# Canonical Homebrew formula for the dayfold CLI (ADR 0031).
#
# This is the source of truth; the operator copies it to
# SloopWorks/homebrew-tap:Formula/dayfold.rb during one-time setup, after which the
# release workflow (.github/workflows/release-cli.yml) bumps `url` + `sha256` on each
# `cli-v<semver>` tag. Install: `brew install sloopworks/tap/dayfold`.
#
# Zero user config (ADR 0031): `depends_on "openjdk"` lets brew install Java
# automatically; brew's openjdk is keg-only, so `write_env_script` pins JAVA_HOME into
# the launcher — the user never installs a JDK or sets JAVA_HOME/PATH.
class Dayfold < Formula
  # Must not start with the formula name (brew audit --strict).
  desc "Content-authoring CLI for the Dayfold household dashboard"
  homepage "https://github.com/SloopWorks/dayfold"
  url "https://github.com/SloopWorks/dayfold/releases/download/cli-v0.1.0/dayfold-0.1.0.tar"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000" # set by the first release bump
  # CLI is Apache-2.0 (ADR 0032; see /LICENSING.md). Effective only once the operator
  # accepts ADR 0032 (counsel) + the repo goes public per the §5 pre-flight.
  license "Apache-2.0"

  # Pin the JDK major to what the CLI is built+tested against (jvmToolchain 17). The
  # dependency and the java_home() arg are a matched pair — change both or neither, or
  # JAVA_HOME resolves to a non-existent keg. (Unpinned `openjdk` would silently swap
  # majors under users on `brew upgrade`.)
  depends_on "openjdk@17"

  def install
    # The release tarball has a single top-level dayfold-<version>/ dir (bin/ + lib/).
    # Homebrew STRIPS that single root on extract, so the staged contents are bin/ +
    # lib/ at the CWD root — handle both strip (normal) and no-strip defensively.
    # (A bare `Dir["dayfold-*/*"]` matches nothing post-strip → empty libexec → the
    # launcher can't find libexec/bin/dayfold. Verified against Homebrew 6.x.)
    staged = Dir["dayfold-*"].first
    libexec.install staged ? Dir["#{staged}/*"] : Dir["*"]
    # Wrap the dist launcher so the runtime is pinned — no user JAVA_HOME needed.
    (bin/"dayfold").write_env_script libexec/"bin/dayfold",
                                     JAVA_HOME: Language::Java.java_home("17")
  end

  test do
    # The launcher must land on PATH and run (guards the `rk` empty-bin/ class of bug).
    # `dayfold` with no args prints usage and exits 2.
    assert_match "usage: dayfold", shell_output("#{bin}/dayfold 2>&1", 2)
  end
end
