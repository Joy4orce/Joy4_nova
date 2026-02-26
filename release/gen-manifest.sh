#!/bin/bash

# Generate a manifest.xml pinning all components to their current revisions.
# Detects whether the workspace is managed by repo or git submodules and
# uses the appropriate method. Output goes to stdout.

set -e

cd "$(dirname "$0")/.."
prefix=$(pwd)

# Detect repo vs git submodule workspace
if [ -d "$prefix/.repo" ]; then
    # repo-managed workspace: use repo manifest -r
    repo manifest -r
    exit 0
fi

# git submodule workspace: generate manifest from .gitmodules

# e.g., ../aos-Video.git -> aos-Video
get_repo_name() {
    local url="$1"
    basename "$url" .git
}

# Get branch from .gitmodules, fallback to "master"
get_branch() {
    local path="$1"
    local branch
    branch=$(git config -f .gitmodules --get "submodule.${path}.branch" 2>/dev/null || true)
    echo "${branch:-master}"
}

# Get the GitHub repo URL from .gitmodules
get_url() {
    local path="$1"
    git config -f .gitmodules --get "submodule.${path}.url"
}

# Get current commit SHA of a submodule
get_revision() {
    local path="$1"
    git -C "$path" rev-parse HEAD
}

# Parent repo (AVP) info - read from AVP subdirectory
avp_revision=$(git -C AVP rev-parse HEAD)
avp_branch=$(git -C AVP branch --show-current)

cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <remote name="github" fetch="."/>

  <default remote="github" revision="master"/>

  <project name="aos-AVP" path="AVP" revision="${avp_revision}" upstream="${avp_branch}" dest-branch="${avp_branch}">
    <copyfile src="Makefile" dest="Makefile"/>
  </project>
EOF

# Iterate over submodules, skipping prebuilt/*
git config -f .gitmodules --get-regexp '^submodule\..*\.path$' | while read -r key path; do
    # Skip prebuilt submodules
    case "$path" in
        native/prebuilt/*) continue ;;
    esac

    url=$(get_url "$path")
    repo_name=$(get_repo_name "$url")
    branch=$(get_branch "$path")
    revision=$(get_revision "$path")

    echo "  <project name=\"${repo_name}\" path=\"${path}\" revision=\"${revision}\" upstream=\"${branch}\" dest-branch=\"${branch}\"/>"
done

echo "</manifest>"
