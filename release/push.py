#!/usr/bin/env python3.11

from github import Github
from configparser import ConfigParser
import os
import sys

selfpath = os.path.dirname(os.path.abspath(sys.argv[0]))
config = ConfigParser()
config.read(os.path.join(selfpath, "config.ini"))

# Detect repo vs git submodule workspace
# repo mode: push.py is at <workspace>/AVP/release/
# submodule mode: push.py is at <workspace>/release/
repo_marker = os.path.join(selfpath, '..', '..', '.repo')
if os.path.isdir(repo_marker):
    prefix = os.path.normpath(os.path.join(selfpath, '..', '..'))
else:
    prefix = os.path.normpath(os.path.join(selfpath, '..'))

g = Github(config['github']['token'])

repo = g.get_organization("nova-video-player").get_repo("aos-AVP")

print(repo.name)

#changelog is in whatsnew
whatsnew_path = os.path.join(prefix, 'Video/src/noamazon/play/release-notes/en-US/internal.txt')
with open(whatsnew_path, 'r') as whatsnew:
    changelog=whatsnew.read()
#    changelog=whatsnew.read().replace('^', '- ')
#    changelog=whatsnew.read().replace('\n', '')

#create_git_release(tag, name, message, draft=False, prerelease=False, target_commitish=NotSet)
release = repo.create_git_release(sys.argv[1], sys.argv[2], changelog, draft = True)
folder = sys.argv[3]
print("Made release " + str(release))
for f in os.listdir(folder):
    path = os.path.join(folder, f)
    if not os.path.isfile(path):
        continue
    if f.endswith(".apk"):
        release.upload_asset(path)
    if f.endswith("manifest.xml"):
        release.upload_asset(path)
