#!/usr/bin/env python3
"""
Sync CHANGELOG.md with GitHub releases from nova-video-player/aos-AVP
Fetches releases from GitHub and adds any missing entries to CHANGELOG.md
"""

import re
import sys
from datetime import datetime
from typing import Dict, List, Set
import urllib.request
import urllib.error
import json


def fetch_github_releases(repo_owner: str, repo_name: str) -> List[Dict]:
    """Fetch all releases from GitHub API"""
    releases = []
    page = 1
    per_page = 100

    while True:
        url = f"https://api.github.com/repos/{repo_owner}/{repo_name}/releases?page={page}&per_page={per_page}"

        try:
            req = urllib.request.Request(url)
            req.add_header('Accept', 'application/vnd.github.v3+json')

            with urllib.request.urlopen(req) as response:
                data = json.loads(response.read().decode())

                if not data:
                    break

                releases.extend(data)

                if len(data) < per_page:
                    break

                page += 1

        except urllib.error.HTTPError as e:
            print(f"Error fetching releases: {e}", file=sys.stderr)
            if e.code == 403:
                print("Rate limit exceeded. Try again later or use a GitHub token.", file=sys.stderr)
            sys.exit(1)

    return releases


def parse_existing_changelog(changelog_path: str) -> Set[str]:
    """Parse CHANGELOG.md and return set of existing version tags"""
    existing_versions = set()

    try:
        with open(changelog_path, 'r', encoding='utf-8') as f:
            content = f.read()

        # Find all version tags in format: <a id="v6.3.28"></a>
        pattern = r'<a id="(v[\d.]+)"></a>'
        matches = re.findall(pattern, content)
        existing_versions.update(matches)

    except FileNotFoundError:
        print(f"CHANGELOG.md not found at {changelog_path}", file=sys.stderr)

    return existing_versions


def format_release_entry(release: Dict) -> str:
    """Format a GitHub release as a CHANGELOG.md entry"""
    tag = release['tag_name']
    name = release['name'] or f"{tag} release"

    # Parse published date
    published_at = release['published_at']
    date_obj = datetime.strptime(published_at, '%Y-%m-%dT%H:%M:%SZ')
    date_str = date_obj.strftime('%Y-%m-%d')

    # Get body and clean it up
    body = release.get('body', '').strip()
    if not body:
        body = "- Release notes not available"

    # Format the entry
    entry = f"""<a id="{tag}"></a>
## [{name}](https://github.com/nova-video-player/aos-AVP/releases/tag/{tag}) - {date_str}

{body}

[Changes][{tag}]


"""

    return entry


def get_changelog_header() -> str:
    """Return the standard CHANGELOG.md header"""
    return "# Nova Video Player changelog\n\n"


def update_changelog(changelog_path: str, new_entries: List[str]):
    """Update CHANGELOG.md with new entries at the top"""
    try:
        with open(changelog_path, 'r', encoding='utf-8') as f:
            content = f.read()
    except FileNotFoundError:
        content = get_changelog_header()

    # Find where to insert (after the header)
    header = "# Nova Video Player changelog\n\n"

    if content.startswith(header):
        # Insert new entries after header
        updated_content = header + ''.join(new_entries) + content[len(header):]
    else:
        # No header found, prepend everything
        updated_content = get_changelog_header() + ''.join(new_entries) + content

    # Write back
    with open(changelog_path, 'w', encoding='utf-8') as f:
        f.write(updated_content)


def main():
    repo_owner = "nova-video-player"
    repo_name = "aos-AVP"
    changelog_path = "CHANGELOG.md"

    print(f"Fetching releases from {repo_owner}/{repo_name}...")
    releases = fetch_github_releases(repo_owner, repo_name)
    print(f"Found {len(releases)} releases on GitHub")

    print("Parsing existing CHANGELOG.md...")
    existing_versions = parse_existing_changelog(changelog_path)
    print(f"Found {len(existing_versions)} existing entries in CHANGELOG.md")

    # Find missing releases
    missing_releases = []
    for release in releases:
        tag = release['tag_name']
        if tag not in existing_versions:
            missing_releases.append(release)

    if not missing_releases:
        print("✓ CHANGELOG.md is up to date!")
        return

    print(f"\nFound {len(missing_releases)} missing releases:")
    for release in missing_releases:
        print(f"  - {release['tag_name']} ({release['published_at'][:10]})")

    # Sort by published date (newest first)
    missing_releases.sort(key=lambda r: r['published_at'], reverse=True)

    # Format entries
    new_entries = [format_release_entry(release) for release in missing_releases]

    print("\nUpdating CHANGELOG.md...")
    update_changelog(changelog_path, new_entries)
    print(f"✓ Added {len(missing_releases)} new entries to CHANGELOG.md")


if __name__ == "__main__":
    main()
